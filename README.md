# neighbors-cp-graph-gen

Constraint programming models (IBM CP Optimizer) that enumerate, up to isomorphism, the simple
undirected graphs with a given **degree sequence**. Two encodings of the same graph are
compared:

| Model | Encoding | Symmetry breaking | Connectivity (optional) |
|---|---|---|---|
| **Adj** | n×n 0/1 adjacency matrix | `optLexAdj` (OptLex), `optRevLexAdj` (OptRevLex) | upper off-diagonal (OptRevLex only) |
| **Nei** | n×D neighbor lists, D = max degree | `optRevLexNeiDir`: its own order on the lists | largest neighbor above i |

Full documentation, with formulas, examples and benchmark results: open **`decs.html`** in a
browser (tabs *Adj model*, *Nei model*, *Adj vs Nei*).

## The Nei model in short

Row i lists the neighbors of vertex i as labels 1..n, strictly increasing, followed by 0 in the
unused slots: neighbors {2, 5} with D = 4 → `2 5 0 0`. The zero slots and the forbidden
self-loop label are fixed in the variable domains; the only base constraints are the strict
order inside a row and the symmetry `count(row u, v+1) = count(row v, u+1)`.

**Symmetry breaking** (`addOptRevLexNeiDir`): for every pair i < j of **equal degree**, rows
increase from top to bottom, compared from the first slot:

```
i, j adjacent:      row i without j+1  ≤lex  row j without i+1   (one slot shorter)
i, j not adjacent:  row i              ≤lex  row j
```

`dropLabel` really deletes the slot holding the label (the following slots move left, no 0 is
added). On regular sequences this order keeps exactly the labelled graphs of `optRevLexAdj`
with the labels reversed (v → n+1−v), so it gives the same solution counts.

**Connectivity** (`addLargestNeighborAbove`): for every vertex except the last, the largest
neighbor (slot deg[i]−1) is above i.

## Status and known limits

- Symmetry breaking is posted only between vertices of equal degree, which keeps it sound for
  any degree sequence (brute force, all graphic sequences with n ≤ 6). It is not complete: a
  graph can be returned more than once.
- Connectivity is a labelling condition, so it can lose connected graphs:
  - Adj (upper off-diagonal): correct for regular sequences; loses graphs on 7 of the 96
    sequences with n ≤ 6 that have a connected graph (all non-regular, e.g. K2,3).
  - Nei (largest neighbor above): loses graphs on 10 of those 96 sequences, **including regular
    ones** (K3,3 for N6_d3, 2 of the 6 graphs of N8_d4). It is not yet fully compatible with the
    Nei row order.

## Build and run

Requirements: Java 17 and IBM ILOG CPLEX Optimization Studio 22.1.1 (Community edition is
enough), installed in `/opt/ibm/ILOG/CPLEX_Studio_Community2211` (paths in `pom.xml`).

```bash
CPLEX=/opt/ibm/ILOG/CPLEX_Studio_Community2211
CP=$CPLEX/cplex/lib/cplex.jar:$CPLEX/opl/lib/oplall.jar
javac -d target/classes -cp $CP src/main/java/org/example/*.java
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
