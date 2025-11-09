package vmsim.core;

import vmsim.core.policies.EvictionPolicy; // Import the policy
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.locks.ReentrantLock;

public class FrameTable {
    private final ArrayList<Frame> frameList;
    private final int userPoolSize;

    private final EvictionPolicy evictionPolicy;

    private final Map<FileShareKey, Frame> sharedPageCache = new HashMap<>();

    private final ReentrantLock sharedCacheLock = new ReentrantLock();

    private record FileShareKey(String filename, int fileOffset) {}

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
            if (frame.isFree() && !(frame.pinned())) {
                frame.lock.lock();
                try {
                    // Re-check if it's still free after acquiring lock
                    if (frame.isFree() && !frame.pinned()) {
                        // Claim and pin
                        frame.pin();
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

    public void freeFrame(Frame frame, PTE pte) {
        frame.lock.lock();
        try {
            if (!frame.pinned()) {
                // System.err.println("WARNING: freeFrame called on unpinned frame!");
                // This should not happen as the caller (spt_destory) would have pinned
                throw new RuntimeException("Unpinned frame is being freed");
            }
            frame.ptes.remove(pte);

            frame.unpin(); // We are done with our job we can unpin it
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

    public boolean frame_get_page(PTE pte, List<String> opLog, boolean isWrite) {
        // Caller holds the PTE lock
        if (pte.inFrame) {
            throw new RuntimeException("Loading a page in frame");
        }

        if (pte.type == PTE.PageType.FILE && !pte.writable) {
            log("SHAREABLE PAGE!", opLog);
            // It's a shareable file-backed page.
            return frame_get_shared_page(pte, opLog, isWrite);
        } else {
            log("NOT SHAREABLE PAGE!", opLog);
            // It's an unshareable anonymous page.
            return frame_get_private_page(pte, opLog, isWrite);
        }
    }

    private boolean frame_get_shared_page(PTE pte, List<String> opLog, boolean isWrite) {
        FileShareKey key = new FileShareKey(pte.filename, pte.fileOffset);
        Frame frameToShare = null;

        this.sharedCacheLock.lock();
        try {
            frameToShare = sharedPageCache.get(key);

            boolean cacheMiss = true;

            if (frameToShare != null) {
                // We found a *potential* match. Lock the frame to validate it.
                frameToShare.lock.lock();
                try {
                    boolean isStale = false;
                    if (frameToShare.isFree()) {
                        // Case 1: Frame was evicted and we got the lock just before it is loaded
                        // In this condition the frame would be pinned and free
                        isStale = true;
                    } else {
                        // Case 2: Frame is occupied, we got the lock after it got evicted and loaded
                        // Get its "representative" PTE.
                        PTE repPTE = frameToShare.ptes.getFirst(); // Safe, not free

                        // Check if the data in the frame matches the key we looked up.
                        if (repPTE.type != PTE.PageType.FILE ||
                                !repPTE.filename.equals(pte.filename) ||
                                repPTE.fileOffset != pte.fileOffset)
                        {
                            // Frame was re-used for a different file or an anonymous page.
                            isStale = true;
                        }
                    }


                    if (isStale) {
                        // The cache entry is bad. Remove it.
                        log("Cache hit on stale frame " + frameToShare.kpage + ". Reloading.", opLog);
                        sharedPageCache.remove(key); // Lazy cleanup
                    } else {
                        // The frame is valid and contains the correct data.
                        cacheMiss = false;
                        log("Sharing FILE VPN " + pte.vaddr + " in Frame " + frameToShare.kpage, opLog);
                        pte.inFrame = true;
                        pte.frame = frameToShare;
                        frameToShare.ptes.add(pte); // Add ourselves as a sharer

                        pte.accessed = true;
                        pte.dirty = isWrite; // Apply the access

                        this.evictionPolicy.onLoad(frameToShare);
                    }
                } finally {
                    frameToShare.lock.unlock();
                }
            }

            if (cacheMiss) {
                // --- CACHE MISS ---
                // We are still holding the sharedCacheLock.

                int frameIndex = getFrame(opLog); // Returns pinned
                Frame newFrame = frameList.get(frameIndex);

                newFrame.lock.lock();
                try {
                    log("Loading FILE VPN " + pte.vaddr + " (" + pte.filename + ") into Frame " + newFrame.kpage, opLog);

                    // Link PTE to Frame
                    pte.inFrame = true;
                    pte.frame = newFrame;
                    newFrame.ptes.add(pte); // We are the first sharer

                    // Add to cache *before* releasing locks
                    sharedPageCache.put(key, newFrame);

                    // Apply access
                    pte.accessed = true;
                    pte.dirty = isWrite;

                    this.evictionPolicy.onLoad(newFrame);
                } finally {
                    newFrame.unpin();
                    newFrame.lock.unlock();
                }
            }
        } finally {
            this.sharedCacheLock.unlock();
        }
        return true;
    }


    public boolean frame_get_private_page(PTE pte, List<String> opLog, boolean isWrite) {
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
            frame.ptes.add(pte);

            pte.accessed = true; // Set by default on load
            pte.dirty = isWrite;

            this.evictionPolicy.onLoad(frame); // Send notice to eviction policy if required

        } finally {
            frame.unpin();
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
                        PTE pte = frame.ptes.getFirst(); // Representative as they all have the same data
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
                                            frame.pinned(),
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
                                        frame.pinned()
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

    public boolean allFramesNotPinned() {
        for (Frame frame : frameList) {
            if (frame.pinned()) {
                return false;
            }
        }
        return true;
    }
}