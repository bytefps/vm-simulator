package vmsim.core;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;

public class Frame {

    public final int kpage; // Kernel address of the frame

    public final ReentrantLock lock; // Used to sync access to frame

    public List<PTE> ptes; // Pointer back to PTE if in use

    private int pin_count; // Claim frame so eviction ignores

    public long policyTimestamp; // Used in the LRU

    public void unpin() {
        if (pin_count > 0) {
            pin_count--;
        } else {
            throw new RuntimeException("Cannot unpin frame with 0 pins");
        }
    }

    public void pin() {
        this.pin_count++;
    }

    public boolean pinned() {
        return pin_count > 0;
    }


    public Frame(int frameIndex) {
        this.kpage = frameIndex;
        this.lock = new ReentrantLock();
        this.ptes = new ArrayList<PTE>();          // Frame starts empty
        this.pin_count = 0;      // Not pinned by default
        this.policyTimestamp = -1;  // Not loaded yet
    }

    public boolean isFree() {
        return this.ptes.isEmpty();
    }
}