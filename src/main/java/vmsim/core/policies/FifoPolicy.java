package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;

public class FifoPolicy implements EvictionPolicy {

    private ArrayList<Frame> frameList;
    private int userPoolSize;
    private final ConcurrentLinkedQueue<Frame> fifoQueue = new ConcurrentLinkedQueue<>();

    @Override
    public void init(ArrayList<Frame> frameList, int userPoolSize) {
        this.frameList = frameList;
        this.userPoolSize = userPoolSize;
    }

    @Override
    public int findVictim(List<String> opLog) {
        while (true) {
            Frame victimFrame = fifoQueue.poll();
            if (victimFrame == null) {
                // This can happen if queue is empty.
                // Fallback to just grabbing the first frame.
                victimFrame = frameList.get(0);
            }

            victimFrame.lock.lock();
            try {
                // Check 1: Frame must be occupied and not pinned
                if (victimFrame.pinned() || victimFrame.isFree()) {
                    if (!victimFrame.isFree()) {
                        // It's pinned, add it back to the queue
                        fifoQueue.add(victimFrame);
                    }
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
                    // This frame is busy. Add it back to the end of the queue
                    // so we can try it again later, and move on.
                    fifoQueue.add(victimFrame);
                    continue;
                }

                // --- VICTIM CANDIDATE FOUND ---
                // We now hold the frame.lock AND all pte.locks
                try {
                    // Check 3: Re-validate state.
                    if (victimFrame.pinned() || victimFrame.ptes.isEmpty()) {
                        continue;
                    }

                    // Check 4: FIFO logic.
                    // (FIFO doesn't check 'accessed' bit, it's a "dumb" policy.
                    // We found our victim just by polling it.)

                    // --- VICTIM CONFIRMED ---
                    PTE repPTE = locksAcquired.get(0);
                    log("Evicting Frame " + victimFrame.kpage + " (Type: " + repPTE.type + ", Sharers: " + locksAcquired.size() + ")", opLog);

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
        // Do nothing for FIFO
    }

    @Override
    public void onLoad(Frame frame) {
        // Add to the end of the queue when loaded
        fifoQueue.add(frame);
    }

    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable(FIFO): " + message);
    }
}