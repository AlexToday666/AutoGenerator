Single Interactive Test Run
--------------------------------------
Launch an interactive session with your solver via:
```bash
python3 auto_tester.py --command "python3 astar.py"
```
Swap `astar.py` for `backtracking.py` to evaluate the second algorithm.

You can pass extra positional arguments:

- `<count>` — number of maps to process (defaults to `1`). Example: `python3 auto_tester.py 25 --command "python3 astar.py"`.
- `easy`, `normal`, `hard`, or `random` — difficulty level (`easy` if omitted). Example: `python3 auto_tester.py 10 hard --command "python3 backtracking.py"`.
- `map` / `dump` — send the generated map to stderr for debugging. Example: `python3 auto_tester.py map --command "python3 astar.py"`.

Generating Statistical Reports
--------------------------------------
Run `report` mode to gather metrics across many maps and variants:
```bash
python3 auto_tester.py report --maps 1000 --variants 1,2 --command "python3 astar.py"
```

Supported flags:

- `--maps <n>` — sample size per variant (defaults to `1000`).
- `--variants <list>` — comma-separated list of variants (`1` and `2` are supported).
- `--seed <value>` — optional random seed for reproducible data.
- `--command "<command>"` — solver launch command (wrap in quotes if it contains spaces).

After completion, the script prints a table with run counts, wins, losses, and time/move statistics for each variant.

Diagnostics
--------------------------------------
- `ERROR: Unexpected end of stream` means the solver crashed or exited before finishing the dialogue; review stderr for details.
- AutoTester expects the commands `m x y`, `r`, `rr`, and `e <moves>` exactly in the Codeforces format. Any deviation immediately fails the current map.
