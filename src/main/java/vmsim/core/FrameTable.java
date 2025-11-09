package vmsim.core;

import vmsim.core.policies.EvictionPolicy; // Import the policy
import java.util.ArrayList;
import java.util.List;

public class FrameTable {
    private final ArrayList<Frame> frameList;
    private final int userPoolSize;

    private final EvictionPolicy evictionPolicy;

    public FrameTable(int userPoolSize, EvictionPolicy evictionPolicy) {
        assert userPoolSize > 0;
        frameList = new ArrayList<>(userPoolSize);
        this.userPoolSize = userPoolSize;
        this.evictionPolicy = evictionPolicy;

        /* Populate frame List with empty frames */
        for (int i = 0; i < userPoolSize; i++) {
            frameList.add(new Frame(i));
        }

        // Initialize the policy with the frame list
        this.evictionPolicy.init(this.frameList, this.userPoolSize);
    }

    private int findFreeFrame(List<String> opLog) {
        for (int i = 0; i < userPoolSize; i++) {
            Frame frame = frameList.get(i);
            // We only need to try/lock if it looks free
            if (frame.isFree() && !frame.pinned) {
                frame.lock.lock();
                try {
                    // Re-check if it's still free after acquiring lock
                    if (frame.isFree() && !frame.pinned) {
                        // Claim and pin
                        frame.pinned = true;
                        return frame.kpage; // Return the index
                    }
                } finally {
                    frame.lock.unlock();
                }
            }
        }
        return -1; // No free frames found
    }

    private int getFrame(List<String> opLog) {
        int index = findFreeFrame(opLog);
        if (index != -1) {
            log("Found free frame " + index + ", it is now pinned.", opLog);
            return index;
        } else {
            log("No free frames, starting eviction...", opLog);
            return evictFrame(opLog);
        }
    }

    public void freeFrame(Frame frame) {
        frame.lock.lock();
        try {
            if (!frame.pinned) {
                // System.err.println("WARNING: freeFrame called on unpinned frame!");
                // This should not happen as the caller (spt_destory) would have pinned
                throw new RuntimeException("Unpinned frame is being freed");
            }
            frame.pte = null;
            frame.pinned = false; // Now it's truly free
        } finally {
            frame.lock.unlock();
        }
    }

    private int evictFrame(List<String> opLog) {
        // eviction Policy finds a victim and pins it for the caller and returns index
        return this.evictionPolicy.findVictim(opLog);
    }

    public void onAccess(PTE pte) {
        this.evictionPolicy.onAccess(pte);
    }


    public boolean frame_get_page(PTE pte, List<String> opLog) {
        // Caller holds the PTE lock
        Frame frame = null;

        if (pte.inFrame) {
            return true;
        }

        int frameIndex = getFrame(opLog);
        frame = frameList.get(frameIndex);

        frame.lock.lock();
        try {
            if (pte.type == PTE.PageType.ANONYMOUS) {
                if (pte.onSwap) {
                    log("Loading VPN " + pte.vaddr + " from SWAP into Frame " + frame.kpage, opLog);
                    pte.onSwap = false;
                } else {
                    log("Loading ANONYMOUS VPN " + pte.vaddr + " (zero-fill) into Frame " + frame.kpage, opLog);
                }
            } else if (pte.type == PTE.PageType.FILE) {
                log("Loading FILE VPN " + pte.vaddr + " (" + pte.filename + ") into Frame " + frame.kpage, opLog);
            }

            pte.inFrame = true;
            pte.frame = frame;
            frame.pte = pte;

            pte.accessed = true; // Set by default on load
            pte.dirty = false;

            this.evictionPolicy.onLoad(frame); // Send notice to eviction policy if required

        } finally {
            frame.pinned = false;
            frame.lock.unlock();
        }
        return true;
    }


    private void log(String message, List<String> opLog) {
        opLog.add("FrameTable: " + message);
    }


    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder();
        sb.append("--- Frame Table (Size: ").append(userPoolSize).append(") ---\n");

        for (int i = 0; i < userPoolSize; i++) {
            Frame frame = frameList.get(i);
            sb.append(String.format("Frame %02d: ", frame.kpage));

            if (frame.lock.tryLock()) {
                try {
                    if (frame.isFree()) {
                        sb.append("[ FREE ]\n");
                    } else {
                        PTE pte = frame.pte;
                        if (pte == null) {
                            sb.append("[ ERROR: Occupied but no PTE ]\n");
                        } else {
                            if (pte.lock.tryLock()) {
                                try {
                                    sb.append(String.format(
                                            "[ PID: %-12s | VPN %02d | %-4s | Pinned: %-5s | Dirty: %-5s | Accessed: %-5s ]\n",
                                            pte.processID,
                                            pte.vaddr,
                                            pte.type == PTE.PageType.FILE ? "FILE" : "ANOM",
                                            frame.pinned,
                                            pte.dirty,
                                            pte.accessed
                                    ));
                                } finally {
                                    pte.lock.unlock();
                                }
                            } else {
                                sb.append(String.format(
                                        "[ PID: %-12s | VPN %02d | Pinned: %-5s | PTE_LOCKED ]\n",
                                        pte.processID,
                                        pte.vaddr,
                                        frame.pinned
                                ));
                            }
                        }
                    }
                } finally {
                    frame.lock.unlock();
                }
            } else {
                sb.append("[ FRAME_LOCKED ]\n");
            }
        }
        sb.append("------------------------------------");
        return sb.toString();
    }
}