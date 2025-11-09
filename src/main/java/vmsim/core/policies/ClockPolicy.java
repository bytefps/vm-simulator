package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;

public class ClockPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private int clockHand = 0;

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimFrame = frameList.get(clockHand);
            advanceClockHand();

            victimFrame.lock.lock();
            try {
                // Check 1: Frame must be occupied and not pinned
                // We use your new Frame.java logic: isFree() checks ptes.isEmpty()
                if (victimFrame.pinned() || victimFrame.isFree()) {
                    continue;
                }

                // Create a stable list of PTEs to check
                // We hold the frame lock, so this list is safe from modification
                List<PTE> ptesToEvict = new ArrayList<>(victimFrame.ptes);
                if (ptesToEvict.isEmpty()) {
                    continue; // Frame became free while we were waiting
                }

                // Check 2: ATOMICALLY acquire ALL PTE locks
                // This is your new, correct "wind back down" logic
                List<PTE> locksAcquired = new ArrayList<>();
                boolean allLocksAcquired = true;

                for (PTE pte : ptesToEvict) {
                    if (!pte.lock.tryLock()) {
                        // FAILURE. We can't get all the locks.
                        allLocksAcquired = false;
                        // "Wind back down" - release all locks we *did* get, in reverse
                        for (int i = locksAcquired.size() - 1; i >= 0; i--) {
                            locksAcquired.get(i).lock.unlock();
                        }
                        break; // Exit the for-loop
                    }
                    // Success, add to our list
                    locksAcquired.add(pte);
                }

                if (!allLocksAcquired) {
                    // This releases the frame.lock (in finally) and
                    // moves to the next frame in the clock sweep.
                    continue;
                }

                // --- VICTIM CANDIDATE FOUND ---
                // We now hold the frame.lock AND all pte.locks for this frame.
                // We are 100% safe from all other threads.
                try {
                    // Check 3: Re-validate state.
                    // Did the frame get pinned or freed while we were waiting?
                    if (victimFrame.pinned() || victimFrame.ptes.isEmpty()) {
                        continue;
                    }

                    // Check 4: The Clock "Second Chance" logic.
                    // We must check if *any* of the sharers have been accessed.
                    boolean wasAccessed = false;
                    for (PTE pte : locksAcquired) {
                        if (pte.accessed) {
                            wasAccessed = true;
                            break;
                        }
                    }

                    if (wasAccessed) {
                        // Give 2nd chance: clear all accessed bits
                        for (PTE pte : locksAcquired) {
                            pte.accessed = false;
                        }
                        continue; // Move to the next frame
                    }

                    // --- VICTIM CONFIRMED ---
                    // We hold all locks, and wasAccessed is false.

                    // Use the first PTE as a "representative" for logging
                    PTE repPTE = locksAcquired.getFirst();
                    log("Evicting Frame " + victimFrame.kpage + " (Type: " + repPTE.type + ", Sharers: " + locksAcquired.size() + ")", opLog);

                    // Handle Writeback
                    // Note: Anonymous pages (ANOM) will never have >1 sharer.
                    if (repPTE.type == PTE.PageType.FILE) {
                        // A shared file page. Check if *any* sharer marked it dirty.
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
                        // This is an ANOM page. There is only one sharer.
                        if (repPTE.dirty) {
                            log("WRITEBACK: ANONYMOUS Page " + repPTE.vaddr + " is dirty, saving to swap.", opLog);
                            repPTE.onSwap = true;
                        }
                    }

                    // The "Shootdown" - Invalidate all PTEs that were sharing this frame
                    for (PTE pte : locksAcquired) {
                        pte.inFrame = false;
                        pte.frame = null;
                    }

                    // Clear the frame's list of sharers
                    victimFrame.ptes.clear();

                    // Pin the frame for the caller (frame_get_page)
                    victimFrame.pin();

                    return victimFrame.kpage;

                } finally {
                    // Release all the PTE locks we acquired
                    for (PTE pte : locksAcquired) {
                        pte.lock.unlock();
                    }
                }
            } finally {
                victimFrame.lock.unlock();
            }
        }
    }

    private void advanceClockHand() {
        clockHand = (clockHand + 1) % userPoolSize;
    }

    @Override
    public void onAccess(PTE pte) {
        // This is the correct, simple logic.
        // We only need to mark the *one* PTE that was hit.
        // The evictor (findVictim) will correctly check all sharers.
        pte.accessed = true;
    }

    @Override
    public void onLoad(Frame frame) {
        // Do nothing for Clock
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(Clock): " + message);
    }
}