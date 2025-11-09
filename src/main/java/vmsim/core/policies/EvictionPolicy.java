package vmsim.core.policies;

import vmsim.core.Frame;
import vmsim.core.PTE;
import java.util.ArrayList;
import java.util.List;

/**
 * An interface defining the "Strategy" for a page eviction policy.
 * This allows the FrameTable to be independent of the algorithm used.
 */
public interface EvictionPolicy {

    /**
     * Initializes the policy with the frame list it will manage.
     * This is called by the FrameTable's constructor.
     *
     * @param frameList The global list of all physical frames.
     * @param userPoolSize The total size of the frame list.
     */
    void init(ArrayList<Frame> frameList, int userPoolSize);

    /**
     * Finds a victim frame, evicts its contents, and returns it.
     * This method MUST be thread-safe.
     * Consider race between newly chosen frame and another eviction process.
     * Good idea to pin the frame you have chosen to evict and want to return.
     *
     * @param opLog The log collector for the calling operation.
     * @return The victim frame, which MUST be pinned and locked.
     */
    int findVictim(List<String> opLog);

    /**
     * A hook called by the SPT (via FrameTable) when a page is *hit*.
     * This is used by LRU and Clock policies to update their state.
     *
     * @param pte The PTE of the page that was just hit.
     */
    void onAccess(PTE pte);

    /**
     * A hook called by the FrameTable when a page is *loaded* into a frame.
     * This is used by FIFO and LRU policies.
     *
     * @param frame The frame that was just loaded into.
     */
    void onLoad(Frame frame);
}