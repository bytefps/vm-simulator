# Multi-threaded Virtual Memory Simulator

This is a multi-threaded application written in Java to simulate operating systems virtual memory allocation,
inspired by my implementation within the Pintos operating system.

# Core features
- Each .txt file acts as its own process
- Realistic fault handling, distinguishes between page faults, seg faults and protection faults
- Shared frame table
- Lazy page loading
- A process 'dies' once its entire file is read
- Outputs are synced to prevent interleaving between multiple threads.
- Files loaded with read only are shareable between processes

# How to run
- Run by passing the command line arguments in the following format
- <num_frames> [policy] <trace_file1> <trace_file2>...
- e.g. java vmsim.Simulator 4 fifo trace_t1.txt trace_t2.txt
- Number of frames is required
- Policy is optional, defaults to clock replacement
- Available policies are clock, fifo and lru
- To implement your own policy extend the EvictionPolicy interface.
- Provide any number of files to trace.

# Trace file format
The trace file uses 4 commands, each line contains one command each on a separate line
- A <address> <W|R>: Allocates an anonymous page in the page table (e.g. A 1000 W)
- F <address> <file> <offset> <W|R>: Allocates a file-backed page in the page table (e.g., F 2000 data.txt 0 R)
- R <address>: Read from an allocated page (e.g. R 1000)
- W <address>: Write to an allocated page (e.g., W 1000)
- C <new_addr> <source_addr>: Creates a virtual copy of source_addr at new_addr (e.g. C 3000 1000)
