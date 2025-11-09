package vmsim;

// --- CORE IMPORTS ---
import vmsim.core.FrameTable;
// --- POLICY IMPORTS ---
import vmsim.core.policies.ClockPolicy;
import vmsim.core.policies.EvictionPolicy;
import vmsim.core.policies.FifoPolicy;
import vmsim.core.policies.LruPolicy;
// ------------------------------

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;


/**
 * The main simulation engine. This class acts as the "kernel".
 * It owns the global, shared hardware (FrameTable) and statistics.
 * It is also the main entry point for the program.
 */
public class Simulator {

    // Shared "hardware"
    private final FrameTable frameTable;

    // Global Statistics
    private final Statistics stats = new Statistics();
    private final ReentrantLock statsLock = new ReentrantLock();

    /**
     * Initializes the simulator with a set number of physical frames
     * and a specific eviction policy.
     */
    public Simulator(int numPhysicalFrames, EvictionPolicy evictionPolicy) {
        // Pass the chosen policy to the FrameTable
        this.frameTable = new FrameTable(numPhysicalFrames, evictionPolicy);
        log("Simulator initialized with " + numPhysicalFrames + " physical frames.");
    }

    /**
     * Public getter for the kernel's frame table.
     * Needed by processes to create their SPT.
     */
    public FrameTable getFrameTable() {
        return this.frameTable;
    }

    /**
     * Public getter for the kernel's statistics module.
     * Needed by processes to create their SPT.
     */
    public Statistics getStats() {
        return this.stats;
    }

    /**
     * Prints the final simulation statistics.
     */
    public void printStatistics() {
        log("\n--- Simulation Complete ---");
        log(stats.getSummary());
    }

    /**
     * A simple, synchronized logger for *setup* and *shutdown* messages.
     * This is public so processes can log fatal errors.
     */
    public void log(String message) {
        // Synchronize on stdout to prevent interleaved log messages
        synchronized (System.out) {
            System.out.println(message);
        }
    }

    /**
     * Public helper class to store stats.
     * It is public so it can be passed to the SPT.
     */
    public class Statistics {
        private int hits = 0;
        private int faults = 0;
        private int accessCounter = 0;

        public int atomicallyGetNextAccessNum() {
            statsLock.lock();
            try {
                accessCounter++;
                return accessCounter;
            } finally {
                statsLock.unlock();
            }
        }

        public void recordHit() {
            statsLock.lock();
            try {
                hits++;
            } finally {
                statsLock.unlock();
            }
        }

        public void recordFault() {
            statsLock.lock();
            try {
                faults++;
            } finally {
                statsLock.unlock();
            }
        }

        public int getTotalAccesses() {
            statsLock.lock();
            try {
                return accessCounter;
            } finally {
                statsLock.unlock();
            }
        }

        public String getSummary() {
            statsLock.lock();
            try {
                int total = accessCounter;
                int currentHits = hits;
                int currentFaults = faults;

                if (total != (currentHits + currentFaults)) {
                    total = currentHits + currentFaults;
                }

                double hitRate = (total == 0) ? 0 : (100.0 * currentHits / total);
                return String.format(
                        "Total Accesses: %d\nPage Hits:      %d\nPage Faults:    %d\nHit Rate:         %.2f%%",
                        total, currentHits, currentFaults, hitRate
                );
            } finally {
                statsLock.unlock();
            }
        }
    }

    /**
     * A factory method to create the correct policy object.
     */
    private static EvictionPolicy createPolicy(String policyName) {
        if (policyName == null) policyName = "clock";
        switch (policyName.toLowerCase()) {
            case "fifo":
                System.out.println("Using FIFO Eviction Policy");
                return new FifoPolicy();
            case "lru":
                System.out.println("Using LRU Eviction Policy");
                return new LruPolicy();
            case "clock":
            default:
                System.out.println("Using Clock Eviction Policy");
                return new ClockPolicy();
        }
    }

    /**
     * Main driver for the VM simulator
     * This method now acts as the "kernel," starting multiple
     * simulated processes based on the command-line arguments.
     */
    public static void main(String[] args) {

        // --- PARSING LOGIC ---
        if (args.length < 2) {
            System.err.println("Usage: java vmsim.Simulator <num_frames> [policy] <trace_file_1> ... <trace_file_n>");
            System.err.println("   <num_frames>: (Required) A positive integer (e.g., 8).");
            System.err.println("   [policy]:     (Optional) 'clock', 'lru', or 'fifo'. Defaults to 'clock'.");
            System.err.println("   <trace_file>: (Required) At least one trace file path (e.g., trace_t1.txt).");
            System.err.println("\nArguments can be in any order.");
            return;
        }

        int numPhysicalFrames = -1;
        String policyName = "clock"; // Default policy
        List<String> traceFiles = new ArrayList<>();

        for (String arg : args) {
            try {
                // 1. Try to parse as frame count
                int frames = Integer.parseInt(arg);
                if (frames > 0) {
                    numPhysicalFrames = frames;
                    continue; // Found it, move to next arg
                }
            } catch (NumberFormatException e) {
                // Not an integer, continue to next checks
            }

            // 2. Try to parse as policy
            String lowerArg = arg.toLowerCase();
            if (lowerArg.equals("clock") || lowerArg.equals("lru") || lowerArg.equals("fifo")) {
                policyName = lowerArg;
                continue; // Found it, move to next arg
            }

            // 3. If it's not a frame count or policy, it's a file
            traceFiles.add(arg);
        }

        // --- VALIDATION ---
        if (numPhysicalFrames == -1) {
            System.err.println("FATAL: You must provide a valid, positive integer for <num_frames>.");
            return;
        }
        if (traceFiles.isEmpty()) {
            System.err.println("FATAL: You must provide at least one trace file.");
            return;
        }
        // --- END PARSING & VALIDATION ---


        // 1. Create the policy
        EvictionPolicy policy = createPolicy(policyName);

        // 2. Initialize the ONE shared Simulator
        Simulator sharedSimulator = new Simulator(numPhysicalFrames, policy);

        // 3. Create a list of Threads
        List<Thread> threads = traceFiles.stream()
                .map(fileName -> {
                    // Pass the "kernel" (sharedSimulator) to the process
                    SimulatedProcess process = new SimulatedProcess(sharedSimulator, fileName);
                    return new Thread(process, "Process-" + fileName);
                })
                .toList();

        // 4. Start all the threads
        System.out.println("--- Starting Multi-Threaded Simulation with " + threads.size() + " processes ---");
        for (Thread t : threads) {
            t.start();
        }

        // 5. Wait for ALL threads to finish (join)
        try {
            for (Thread t : threads) {
                t.join();
            }
        } catch (InterruptedException e) {
            System.err.println("Main thread interrupted while waiting for processes.");
            e.printStackTrace();
            Thread.currentThread().interrupt(); // Restore interrupted status
        }

        System.out.println(  "\n---- Final Frame Table ----\n" +sharedSimulator.frameTable);

        if (sharedSimulator.frameTable.allFramesNotPinned()) {
            System.out.println("ALL FRAMES NOT PINNED :) !");
        } else {
            System.out.println("THERE IS A PINNED FRAME :(");
        }

        // 6. Print final statistics
        sharedSimulator.printStatistics();
    }
}