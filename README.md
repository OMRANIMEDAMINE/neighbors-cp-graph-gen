# neighbors-cp-graph-gen

Constraint programming models (IBM CP Optimizer) that enumerate, up to isomorphism, the simple
undirected graphs with a given **degree sequence**. Two encodings of the same graph are
compared:

| Model | Encoding | Symmetry breaking | Connectivity (optional) |
|---|---|---|---|
| **Adj** | n×n 0/1 adjacency matrix | `optLexAdj` (OptLex), `optRevLexAdj` (OptRevLex) | upper off-diagonal (OptRevLex only) |
| **Nei** | n×D neighbor lists, D = max degree | `optRevLexNeiDir`: OptRevLex of Adj, posted on the lists | largest neighbor above i |

Full documentation, with formulas, examples and benchmark results: open **`decs.html`** in a
browser (tabs *Adj model*, *Nei model*, *Adj vs Nei*).

## The Nei model in short

Row i lists the neighbors of vertex i as labels 1..n, strictly increasing, followed by 0 in the
unused slots: neighbors {2, 5} with D = 4 → `2 5 0 0`. The zero slots and the forbidden
self-loop label are fixed in the variable domains; the only base constraints are the strict
order inside a row and the symmetry `count(row u, v+1) = count(row v, u+1)`.

**Symmetry breaking** (`addOptRevLexNeiDir`): for every pair i < j of **equal degree** d, keep
the d neighbor slots of each row, remove the labels i+1 and j+1 from both rows, and compare what
remains **from the last neighbor slot down to the first** (largest neighbor first):

```
i, j adjacent:      rev(row i without j+1)  ≤lex  rev(row j without i+1)   (d-1 slots)
i, j not adjacent:  rev(row i)              ≤lex  rev(row j)               (d slots)
```

Row i never holds i+1, so only j+1 (in row i) and i+1 (in row j) are actually removed, when i
and j are adjacent. `dropLabel` really deletes the slot holding the label (the following slots
move left, no 0 is added). Example on N6_d3: `1: 2 3 4 / 2: 1 5 6 / 3: 1 5 6 / 4: 1 5 6 /
5: 2 3 4 / 6: 2 3 4` is rejected (rows 4 and 5 give `6 1` > `3 2`); the prism
`1: 2 3 4 / 2: 1 3 5 / 3: 1 2 6 / 4: 1 5 6 / 5: 2 4 6 / 6: 3 4 5` is accepted.

Reading the lists largest first decides at the largest vertex that is a neighbor of only one of
i and j, exactly like OptRevLexAdj. Nei and `optRevLexAdj` therefore keep the same labelled
graphs on every degree sequence (same counts on all 278 graphic sequences with n ≤ 6 and on the
whole benchmark set).

**Connectivity** (`addLargestNeighborAbove`): for every vertex except the last, the largest
neighbor (slot deg[i]−1) is above i. This is the Adj upper off-diagonal written on the lists, and
it gives the same connected counts as `optRevLexAdj` with connectivity.

## Status and known limits

- Symmetry breaking is posted only between vertices of equal degree, which keeps it sound for
  any degree sequence (brute force, all graphic sequences with n ≤ 6). It is not complete: a
  graph can be returned more than once.
- Connectivity is a labelling condition, so it can lose connected graphs. Adj (upper
  off-diagonal) and Nei (largest neighbor above) behave the same: correct for regular sequences;
  they lose one connected graph on the same 7 of the 96 sequences with n ≤ 6 that have a
  connected graph (all non-regular, e.g. K2,3 for [2,2,2,3,3]).
- Speed (2026-11-18 run, loaded machine, see `decs.html`): Nei is faster than OptRevLexAdj on
  sparse sequences (N14_d3: 42.8 s against 84.5 s) and much slower on denser ones (N12_d4:
  129.9 s against 28.1 s; N12_d8: 82.1 s against 2.4 s).

## Build and run

Requirements: Java 17 and IBM ILOG CPLEX Optimization Studio 22.1.1 (Community edition is
enough), installed in `/opt/ibm/ILOG/CPLEX_Studio_Community2211` (paths in `pom.xml`).

```bash
CPLEX=/opt/ibm/ILOG/CPLEX_Studio_Community2211
CP=$CPLEX/cplex/lib/cplex.jar:$CPLEX/opl/lib/oplall.jar
javac --release 17 -d target/classes -cp $CP src/main/java/org/example/*.java
java -Djava.library.path=$CPLEX/opl/bin/x86-64_linux -cp target/classes:$CP org.example.Main
```

`Main` runs every model on the benchmark set (26 sequences in 8 families) and prints one table
row per sequence: solutions, time and peak memory per model. Arguments replace the default set,
one sequence per argument, degrees separated by commas (sorted increasingly before solving):

```bash
java ... org.example.Main 1,2,2,3 3,3,3,3,3,3
```

Switches at the top of `Main.java`:

- `CONNECTIVITY`: `false` runs OptLexAdj, OptRevLexAdj, OptRevLexNeiDir; `true` runs the
  connected variants OptRevLexAdjCon and OptRevLexNeiDirCon.
- `PRINT_SOLUTIONS`: print every graph found (matrix for Adj, `vertex: neighbors` for Nei).

## Code layout

- `CpModels.java`: the models (one public method per table column), the base models
  (`buildAdjacencyModel`, `buildRevLexNeighborsModel`) and one method per symmetry-breaking or
  connectivity constraint.
- `Main.java`: benchmark driver (sequence set, Erdős–Gallai check, table output).
- `Result.java`: solution count, time and peak memory of one run.
