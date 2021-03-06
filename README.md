MR-PKM
======

Hadoop MapReduce implementation of Pairwise K-mer Mode pipeline for metagenomics

Pairwise K-mer Mode Pipeline Overview
-------------------------------------

Pairwise K-mer Mode Pipeline computes MODE number of pairwise K-mer matches per a metagenomics DNA/RNA read. To match partial DNA/RNA sequence among the given FASTA files, the pipeline uses K-mer. To provide fast K-mer match(search) against another given FASTA file, the pipeline pre-computes K-mers of given FASTA files and generates ReadID Index and Kmer Index of the FASTA file. By using these pre-computed indice, it does fast K-mer match among a group of FASTA files. Once the pipeline found all K-mer matches per a read, it computes MODE number of K-mer hit count per a read.

Build
-----

To build, just type "ant". Without any options, this command will create a light jar package that doesn't include any dependencies.

If you want to build all-in-one jar package file for easy distribution, type "ant package-for-store". This command will create a new "store" directory and create an all-in-one jar package file in it. 

ReadID Index Builder
--------------------

ReadID Index Builder generates an index for OffsetID-ReadID pairs. This index will be used in the later Kmer Index Builder in order to make compact Kmer indices. Once Kmer indices were made, this ReadID Index is not necessary for further matching phase.

Command Line Options
- "--h" : shows help message (optional)
- "--c" : set predefined cluster configuration (optional, default="default")

Command
```
java -cp dist/lib/*:dist/MR-PKM.jar edu.arizona.cs.mrpkm.MRPKM ReadIDIndexBuilder <options> <input FASTA paths> <output path>
```

Kmer Index Builder
------------------

Kmer Index Builder generates an index for Kmer-ReadIDs pairs. This index will be used in the later Kmer match.

Command Line Options
- "--h" : shows help message (optional)
- "--c" : set predefined cluster configuration (optional, default="default")
- "--k" : set kmer size (optional, default=20)
- "--n" : set node size (optional, default=1)
- "--f" : set output format (optional, default="mapfile")
- "--i" : set ReadID Index search paths

Command
```
java -cp dist/lib/*:dist/MR-PKM.jar edu.arizona.cs.mrpkm.MRPKM KmerIndexBuilder --i <ReadID Paths> <other options> <input FASTA paths> <output path>
```

Pairwise Kmer MODE Counter
--------------------------

Pairwise Kmer MODE Counter finds all MODE of Kmer hits per reads in the given a group of Kmer indice.

Command Line Options
- "--h" : shows help message (optional)
- "--c" : set predefined cluster configuration (optional, default="default")
- "--n" : set node size (optional, default=1)
- "--min" : set min hit filter (optional, default=0)
- "--max" : set max hit filter (optional, default=0 == unlimited)

Command
```
java -cp dist/lib/*:dist/MR-PKM.jar edu.arizona.cs.mrpkm.MRPKM PairwiseKmerModeCounter <options> <input Kmer index paths> <output path>
```

Performance Metrics
-------------------

Atmosphere - Small1 type Instance (2 CPUs, 8 GB memory, 60 GB disk + 500GB EBS volume)

ReadID Index Builder

Num. of MapReduce Comp. Nodes | FASTA File Size | Generated Index Size | Time Taken (HDFS) | Time Taken (iRODS)
--- | --- | --- | --- | ---
3 | 22.14GB(4 files) | 2.19GB | 9m47s | -
4 | 22.14GB(4 files) | 2.19GB | 9m35s | 18m26s


Kmer Index Builder

Num. of MapReduce Comp. Nodes | FASTA File Size | Generated Index Size | Time Taken
--- | --- | --- | ---
3 | 22.14GB(4 files) | 80.65GB | 5h44m46s
4 | 22.14GB(4 files) | 80.65GB | 3h44m53s


Pairwise Kmer Mode Counter

Num. of MapReduce Comp. Nodes | Kmer Index Size | Num. of Outputs | Time Taken
--- | --- | --- | ---
3 | 80.65GB(4 files) | 12 | 9h26m5s
4 | 80.65GB(4 files) | 12 | 7h21m3s
