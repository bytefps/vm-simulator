package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

public class LruPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private final AtomicLong accessTime = new AtomicLong(0);

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimCandidate = null;
            long minTime = Long.MAX_VALUE;

            // --- Find LRU Candidate ---
            // This loop is "dirty" - it reads timestamps without locks.
            // This is safe. We will re-validate under lock.
            for (Frame frame : frameList) {
                if (!(frame.pinned()) && !frame.isFree()) {
                    if (frame.policyTimestamp < minTime) {
                        minTime = frame.policyTimestamp;
                        victimCandidate = frame;
                    }
                }
            }

            if (victimCandidate == null) {
                // This shouldn't happen if the table is full, but
                // it's a safe guard.
                continue;
            }

            Frame victimFrame = victimCandidate;
            victimFrame.lock.lock();

            try {
                // Check 1: Re-validate.
                // Did it get pinned, freed, or *accessed* (timestamp changed)
                // while we were waiting for the lock?
                if (victimFrame.pinned() || victimFrame.isFree() || victimFrame.policyTimestamp > minTime) {
                    continue;
                }

                // Create a stable list of PTEs to check
                List<PTE> ptesToEvict = new ArrayList<>(victimFrame.ptes);
                if (ptesToEvict.isEmpty()) {
                    continue; // Frame became free
                }

                // Check 2: ATOMICALLY acquire ALL PTE locks
                List<PTE> locksAcquired = new ArrayList<>();
                boolean allLocksAcquired = true;

                for (PTE pte : ptesToEvict) {
                    if (!pte.lock.tryLock()) {
                        // FAILURE.
                        allLocksAcquired = false;
                        // "Wind back down"
                        for (int i = locksAcquired.size() - 1; i >= 0; i--) {
                            locksAcquired.get(i).lock.unlock();
                        }
                        break; // Exit the for-loop
                    }
                    locksAcquired.add(pte);
                }

                if (!allLocksAcquired) {
                    // This releases the frame.lock (in finally) and
                    // causes us to re-scan for the next LRU candidate.
                    continue;
                }

                // --- VICTIM CANDIDATE FOUND ---
                // We now hold the frame.lock AND all pte.locks
                try {
                    // Check 3: Re-validate state.
                    if (victimFrame.pinned()|| victimFrame.ptes.isEmpty() || victimFrame.policyTimestamp > minTime) {
                        continue;
                    }

                    // Check 4: LRU logic.
                    // (LRU doesn't check 'accessed' bit. The timestamp *is*
                    // the check, and we've already validated it.)

                    // --- VICTIM CONFIRMED ---
                    PTE repPTE = locksAcquired.get(0);
                    log("Evicting Frame " + victimFrame.kpage + " (Type: " + repPTE.type + ", Sharers: " + locksAcquired.size() + ", LRU time=" + victimFrame.policyTimestamp + ")", opLog);

                    // Handle Writeback
                    if (repPTE.type == PTE.PageType.FILE) {
                        boolean isDirty = false;
                        for(PTE pte : locksAcquired) {
                            if (pte.dirty && pte.writable) {
                                isDirty = true;
                                break;
                            }
                        }
                        if (isDirty) {
                            log("WRITEBACK: FILE Page " + repPTE.vaddr + " (offset " + repPTE.fileOffset + ") to " + repPTE.filename, opLog);
                        }
                    } else if (repPTE.type == PTE.PageType.ANONYMOUS) {
                        if (repPTE.dirty) {
                            log("WRITEBACK: ANONYMOUS Page " + repPTE.vaddr + " is dirty, saving to swap.", opLog);
                            repPTE.onSwap = true;
                        }
                    }

                    // The "Shootdown"
                    for (PTE pte : locksAcquired) {
                        pte.inFrame = false;
                        pte.frame = null;
                    }

                    victimFrame.ptes.clear();
                    victimFrame.pin();

                    return victimFrame.kpage;

                } finally {
                    // Release all the PTE locks
                    for (PTE pte : locksAcquired) {
                        pte.lock.unlock();
                    }
                }
            } finally {
                victimFrame.lock.unlock();
            }
        }
    }

    @Override
    public void onAccess(PTE pte) {
        // A page hit *is* an access. Update the timestamp.
        if (pte.frame != null) {
            pte.frame.policyTimestamp = accessTime.getAndIncrement();
        }
    }

    @Override
    public void onLoad(Frame frame) {
        // A page load *is* an access. Update the timestamp.
        frame.policyTimestamp = accessTime.getAndIncrement();
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(LRU): " + message);
    }
}