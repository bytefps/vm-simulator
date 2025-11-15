package vmsim;

import vmsim.core.SPT;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Scanner;
import java.util.concurrent.Phaser;

/**
 * Represents a single "process" that runs on a thread.
 * Each process has its own trace file and its own SPT
 */
public class SimulatedProcess implements Runnable {

    // Global "kernel" services
    private final Simulator simulator;

    // Per-process state
    private final SPT spt;
    private final String processID;
    private final List<String> allCommands; // The *entire* trace file, shared with children
    private int pc; // Program Counter: the index of the *next* command to run
    private final Phaser phaser; // Used to track when all processes are done
    private final boolean isForkedChild;


    // 4KB pages (4096 bytes). 2^12.
    private static final int PAGE_SHIFT = 12;

    /**
     * Constructor for a parent process.
     * Reads the trace file into memory.
     */
    public SimulatedProcess(Simulator simulator, String traceFile, Phaser phaser) throws IOException {
        this.simulator = simulator;
        this.phaser = phaser;
        this.processID = new File(traceFile).getName();
        this.spt = new SPT(this.processID, simulator.getFrameTable(), simulator.getStats());

        // Read the *entire* file into a list. This is our "program".
        this.allCommands = Files.readAllLines(Paths.get(traceFile));
        this.pc = 0; // Starts at the beginning
        this.isForkedChild = false;
    }

    @Override
    public void run() {
        if (isForkedChild) {
            phaser.register(); // Tell the main thread we have started
        }
        try {
            simulator.log("--- Process " + processID + " starting. ---");

            // Main execution loop. We loop from our starting PC.
            for (int i = this.pc; i < allCommands.size(); i++) {
                this.pc = i; // Update our PC
                String line = allCommands.get(i).trim();

                if (line.isEmpty() || line.startsWith("#")) {
                    continue;
                }

                String[] parts = line.split("\\s+");
                if (parts.length < 1) {
                    System.err.println("FATAL: [" + processID + "] Invalid trace line: " + line);
                    return;
                }

                String command = parts[0].toUpperCase();
                int address = -1;
                int pageKey = -1;
                boolean isWritable = false;
                String filename = null;
                int fileOffset = -1;

                // Parse address (if it exists)
                if (parts.length > 1) {
                    try {
                        address = Integer.parseInt(parts[1], 16);
                        pageKey = address >> PAGE_SHIFT;
                    } catch (NumberFormatException e) {
                        System.err.println("FATAL: [" + processID + "] Invalid address: " + parts[1]);
                        return;
                    }
                }

                // R and W commands are wrapped in a try/catch.
                try {
                    switch (command) {
                        case "A": // Allocate Anonymous
                            isWritable = parts[2].equalsIgnoreCase("W");
                            spt.allocateAnonymousPage(pageKey, isWritable);
                            break;

                        case "F": // Allocate File-backed
                            filename = parts[2];
                            fileOffset = Integer.parseInt(parts[3]);
                            isWritable = parts[4].equalsIgnoreCase("W");
                            spt.allocateFilePage(pageKey, filename, fileOffset, isWritable);
                            break;

                        case "R": // Read
                            this.spt.accessMemory(address, false);
                            break;

                        case "W": // Write
                            this.spt.accessMemory(address, true);
                            break;

                        case "C": // Copy-on-Write
                            int newAddress = Integer.parseInt(parts[1].substring(0), 16);
                            int sourceAddress = Integer.parseInt(parts[2].substring(0), 16);
                            int newPageKey = newAddress >> PAGE_SHIFT;
                            int sourcePageKey = sourceAddress >> PAGE_SHIFT;
                            spt.setupCopyOnWrite(newPageKey, sourcePageKey);
                            break;

                        default:
                            System.err.println("FATAL: [" + processID + "] Invalid command: " + command);
                            return;
                    }
                } catch (UnauthorizedAccessException e) {
                    simulator.log("FATAL [PID: " + processID + "]: " + e.getMessage());
                    return; // Kill this thread
                } catch (Exception e) {
                    System.err.println("FATAL: [" + processID + "] Error processing line: " + line);
                    e.printStackTrace();
                    return;
                }
            }
        } catch (Exception e) {
            System.err.println("FATAL: [" + processID + "] Runtime error: " + e);
            e.printStackTrace();
        } finally {
            simulator.log("--- Process " + processID + " finished. Cleaning up resources... ---");
            if (spt != null) {
                spt.destroy(); // Keep this commented for debugging
            }
            phaser.arriveAndDeregister(); // Tell the main thread we are done
        }
    }
}