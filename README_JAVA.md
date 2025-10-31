Single Interactive Test Run
--------------------------------------
Compile the tester once:
```bash
javac AutoTester.java
```
Then run an interactive session with your solver, for example:
```bash
java AutoTester --command "python3 astar.py"
```
Replace `astar.py` with `backtracking.py` to check the second algorithm.

The tester accepts additional positional arguments:

- `<count>` — number of maps to run (defaults to `1`). Example: `java AutoTester 25 --command "python3 astar.py"`.
- `easy`, `normal`, `hard`, or `random` — difficulty selection (`easy` if omitted). Example: `java AutoTester 10 hard --command "python3 backtracking.py"`.
- `map` / `dump` — print the generated map to stderr for debugging. Example: `java AutoTester map --command "python3 astar.py"`.

Generating Statistical Reports
--------------------------------------
Use `report` mode to collect metrics across many maps and variants:
```bash
java AutoTester report --maps 1000 --variants 1,2 --command "python3 astar.py"
```

Available flags:

- `--maps <n>` — sample size per variant (defaults to `1000`).
- `--variants <list>` — comma-separated variant list (`1` and `2` are supported).
- `--seed <value>` — optional random seed for reproducible runs.
- `--command "<command>"` — solver launch command (wrap in quotes if it contains spaces).

After finishing, the tester prints a table summarizing runs, wins, losses, and time/move statistics per variant.

Diagnostics
--------------------------------------
- `ERROR: Unexpected end of stream` indicates that the solver crashed or exited before completing the dialogue; inspect stderr for details.
- AutoTester expects commands `m x y`, `r`, `rr`, and `e <moves>` strictly in the Codeforces format. Any deviation immediately fails the active map.
