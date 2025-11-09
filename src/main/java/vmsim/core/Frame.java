package vmsim.core;

import java.util.concurrent.locks.ReentrantLock;

public class Frame {

    public final int kpage; // Kernel address of the frame

    public final ReentrantLock lock; // Used to sync access to frame

    public PTE pte; // Pointer back to PTE if in use

    public boolean pinned; // Claim frame so eviction ignores

    public long policyTimestamp; // Used in the LRU

    public Frame(int frameIndex) {
        this.kpage = frameIndex;
        this.lock = new ReentrantLock();
        this.pte = null;          // Frame starts empty
        this.pinned = false;      // Not pinned by default
        this.policyTimestamp = -1;  // Not loaded yet
    }

    public boolean isFree() {
        return this.pte == null;
    }
}