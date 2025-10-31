import math
import os
import random
import shlex
import subprocess
import sys
from collections import Counter, defaultdict
from dataclasses import dataclass, field
from enum import Enum
from typing import Callable, Dict, List, Optional, Sequence, Tuple


GRID_SIZE = 13
DX = (-1, 1, 0, 0)
DY = (0, 0, -1, 1)


@dataclass
class ReportConfig:
    enabled: bool = False
    maps_per_variant: int = 1000
    random_seed: Optional[int] = None
    command: List[str] = field(default_factory=lambda: default_command())
    variants: List[int] = field(default_factory=lambda: [1, 2])


@dataclass
class TestResult:
    success: bool
    actual_moves: int
    reported_moves: int
    failure_reason: Optional[str]

    @classmethod
    def success_result(cls, actual_moves: int, reported_moves: int) -> "TestResult":
        return cls(True, actual_moves, reported_moves, None)

    @classmethod
    def failure_result(cls, reason: str, actual_moves: int, reported_moves: int) -> "TestResult":
        return cls(False, actual_moves, reported_moves, reason)


@dataclass
class ExecutionRecord:
    variant: int
    success: bool
    execution_time_ms: float
    actual_moves: int
    reported_moves: int
    failure_reason: Optional[str]


@dataclass
class RunMetrics:
    execution_times_ms: List[float] = field(default_factory=list)
    actual_moves: List[int] = field(default_factory=list)
    reported_moves: List[int] = field(default_factory=list)
    failure_reasons: List[str] = field(default_factory=list)
    wins: int = 0
    losses: int = 0

    def record(self, record: ExecutionRecord) -> None:
        self.execution_times_ms.append(record.execution_time_ms)
        self.actual_moves.append(record.actual_moves)
        if record.reported_moves >= 0:
            self.reported_moves.append(record.reported_moves)
        if record.success:
            self.wins += 1
        else:
            self.losses += 1
            if record.failure_reason:
                self.failure_reasons.append(record.failure_reason)

    def sample_size(self) -> int:
        return self.wins + self.losses

    def total_wins(self) -> int:
        return self.wins

    def total_losses(self) -> int:
        return self.losses

    def win_percentage(self) -> float:
        total = self.sample_size()
        return float("nan") if total == 0 else (self.total_wins() * 100.0) / total

    def loss_percentage(self) -> float:
        total = self.sample_size()
        return float("nan") if total == 0 else (self.total_losses() * 100.0) / total


@dataclass
class StatsSummary:
    mean: float = float("nan")
    median: float = float("nan")
    mode: float = float("nan")
    std_dev: float = float("nan")


class Difficulty(Enum):
    EASY = ("Easy", 1, 2, 3)
    NORMAL = ("Normal", 2, 3, 2)
    HARD = ("Hard", 1, 1, 0)

    def __init__(self, display_name: str, min_vision: int, max_vision: int, safe_distance: int):
        self.display_name = display_name
        self.min_vision = min_vision
        self.max_vision = max_vision
        self._safe_distance = safe_distance

    def random_vision(self, rng: random.Random) -> int:
        if self.min_vision == self.max_vision:
            return self.min_vision
        return rng.randint(self.min_vision, self.max_vision)

    def safe_distance(self) -> int:
        return self._safe_distance

    def __str__(self) -> str:
        return self.display_name

    @staticmethod
    def from_string(value: str) -> "Difficulty":
        normalized = value.strip().upper()
        if normalized in {"EASY", "LIGHT"}:
            return Difficulty.EASY
        if normalized in {"NORMAL", "MEDIUM"}:
            return Difficulty.NORMAL
        if normalized in {"HARD", "ADVANCED", "EXTREME"}:
            return Difficulty.HARD
        raise ValueError(f"Unknown difficulty: {value}")

    @staticmethod
    def random_difficulty(rng: random.Random) -> "Difficulty":
        return rng.choice(list(Difficulty))


@dataclass
class Enemy:
    x: int
    y: int
    type: str


grid: List[List[str]] = []
variant: int = 0
gollum_x: int = 0
gollum_y: int = 0
mount_doom_x: int = 0
mount_doom_y: int = 0
mithril_x: int = -1
mithril_y: int = -1
frodo_x: int = 0
frodo_y: int = 0
ring_on: bool = False
has_mithril: bool = False
found_gollum: bool = False
rand = random.Random()
difficulty: Difficulty = Difficulty.EASY
active_difficulty: Difficulty = Difficulty.EASY
random_difficulty_flag: bool = False
safe_placement_distance: int = 3
dump_map: bool = False
verbose_output: bool = True
enemies: List[Enemy] = []


def default_command() -> List[str]:
    return ["java", "Main"]


def reset_simulation_state() -> None:
    global frodo_x, frodo_y, ring_on, has_mithril, found_gollum
    frodo_x = 0
    frodo_y = 0
    ring_on = False
    has_mithril = False
    found_gollum = False


def log(message: str) -> None:
    if verbose_output:
        print(message)


def log_inline(message: str) -> None:
    if verbose_output:
        print(message, end="", flush=True)


def log_formatted(fmt: str, *args: object) -> None:
    if verbose_output:
        print(fmt % args)


def main(argv: Sequence[str]) -> None:
    report_config = parse_report_config(list(argv))
    if report_config.enabled:
        run_report_mode(report_config)
        return
    run_standard_mode(list(argv), report_config.command)


def run_standard_mode(args: List[str], command_override: Optional[List[str]]) -> None:
    global random_difficulty_flag, difficulty, active_difficulty, dump_map
    test_count = 1
    count_set = False
    difficulty_set = False
    effective_command = list(command_override or default_command())

    i = 0
    while i < len(args):
        arg = args[i]
        if arg.startswith("--command="):
            i += 1
            continue
        if arg.lower() == "--command":
            i += 2
            continue
        if is_map_dump_keyword(arg):
            dump_map = True
            i += 1
            continue

        maybe_count = try_parse_int(arg)
        if not count_set and maybe_count is not None:
            test_count = max(1, maybe_count)
            count_set = True
            i += 1
            continue

        if not difficulty_set:
            if is_random_keyword(arg):
                random_difficulty_flag = True
                difficulty_set = True
                i += 1
                continue
            try:
                difficulty = Difficulty.from_string(arg)
                difficulty_set = True
                i += 1
                continue
            except ValueError:
                pass

        log(f"Ignoring argument '{arg}'")
        i += 1

    if not difficulty_set and not random_difficulty_flag:
        difficulty = Difficulty.EASY

    passed = 0

    for t in range(1, test_count + 1):
        active = Difficulty.random_difficulty(rand) if random_difficulty_flag else difficulty
        set_active_difficulty(active)
        log("\n╔═══════════════════════════════════╗")
        log(f"║       TEST {t} / {test_count}              ║")
        log("╚═══════════════════════════════════╝")

        generate_test()
        result = run_test(list(effective_command))

        if result.success:
            log("✓ PASSED")
            passed += 1
        else:
            log("✗ FAILED")

    log("\n═══════════════════════════════════")
    log(f"Results: {passed} / {test_count} passed")
    log("═══════════════════════════════════\n")


def parse_report_config(args: List[str]) -> ReportConfig:
    config = ReportConfig()

    for arg in args:
        if is_report_keyword(arg):
            config.enabled = True
            break

    command_override: Optional[List[str]] = None
    i = 0
    while i < len(args):
        arg = args[i]
        if arg.startswith("--command="):
            parsed = parse_command_line(arg[len("--command="):])
            if parsed:
                command_override = parsed
            i += 1
            continue
        if arg.lower() == "--command" and i + 1 < len(args):
            parsed = parse_command_line(args[i + 1])
            if parsed:
                command_override = parsed
            i += 2
            continue
        i += 1

    if command_override is not None:
        config.command = command_override

    if not config.enabled:
        return config

    i = 0
    while i < len(args):
        arg = args[i]
        if is_report_keyword(arg):
            i += 1
            continue
        if arg.startswith("--maps="):
            value = try_parse_int(arg[len("--maps="):])
            if value and value > 0:
                config.maps_per_variant = value
            i += 1
            continue
        if arg.lower() == "--maps" and i + 1 < len(args):
            value = try_parse_int(args[i + 1])
            if value and value > 0:
                config.maps_per_variant = value
            i += 2
            continue
        if arg.startswith("--seed="):
            config.random_seed = try_parse_long(arg[len("--seed="):])
            i += 1
            continue
        if arg.lower() == "--seed" and i + 1 < len(args):
            config.random_seed = try_parse_long(args[i + 1])
            i += 2
            continue
        if arg.startswith("--variants="):
            apply_variants(config, arg[len("--variants="):])
            i += 1
            continue
        if arg.lower() == "--variants" and i + 1 < len(args):
            apply_variants(config, args[i + 1])
            i += 2
            continue
        numeric = try_parse_int(arg)
        if numeric and numeric > 0:
            config.maps_per_variant = numeric
        i += 1

    if not config.variants:
        config.variants = [1, 2]
    if not config.command:
        config.command = default_command()
    return config


def apply_variants(config: ReportConfig, value: Optional[str]) -> None:
    if value is None:
        return
    variants: Dict[int, None] = {}
    for token in value.split(","):
        parsed = try_parse_int(token.strip())
        if parsed and parsed > 0:
            variants[parsed] = None
    if variants:
        config.variants = sorted(variants.keys())


def parse_command_line(value: Optional[str]) -> List[str]:
    if value is None:
        return []
    trimmed = value.strip()
    if not trimmed:
        return []
    try:
        return shlex.split(trimmed)
    except ValueError:
        return []


def run_report_mode(config: ReportConfig) -> None:
    if config.random_seed is not None:
        rand.seed(config.random_seed)
    else:
        rand.seed(int.from_bytes(os.urandom(8), "big"))

    metrics_by_variant: Dict[int, RunMetrics] = {}
    total_runs = config.maps_per_variant * len(config.variants)

    print("=== AutoTester Statistical Report ===")
    print(f"Maps per variant: {config.maps_per_variant}")
    print(f"Variants analysed: {config.variants}")
    print(f"Command under test: {command_to_display(config.command)}")
    if config.random_seed is not None:
        print(f"Random seed: {config.random_seed}")
    print(f"Total solver runs: {total_runs}")
    print()

    for variant_value in config.variants:
        metrics = metrics_by_variant.setdefault(variant_value, RunMetrics())
        for map_index in range(config.maps_per_variant):
            generate_test_for_variant(variant_value)
            record = execute_command(config.command)
            metrics.record(record)
            progress_step = max(1, config.maps_per_variant // 10)
            if (map_index + 1) % progress_step == 0:
                print(f"Variant {variant_value}: {map_index + 1} / {config.maps_per_variant} maps processed")
    print_report_summary(config, metrics_by_variant)


def generate_test_for_variant(desired_variant: int) -> None:
    global difficulty, active_difficulty, random_difficulty_flag
    previous_difficulty = difficulty
    previous_active = active_difficulty
    previous_random = random_difficulty_flag
    difficulty = Difficulty.EASY
    set_active_difficulty(Difficulty.EASY)
    random_difficulty_flag = False
    attempts = 0
    while True:
        generate_test()
        attempts += 1
        if variant == desired_variant or attempts >= 10000:
            break
    if variant != desired_variant:
        raise RuntimeError(f"Unable to generate map for variant {desired_variant} after {attempts} attempts")
    difficulty = previous_difficulty
    set_active_difficulty(previous_active)
    random_difficulty_flag = previous_random


def execute_command(command: Sequence[str]) -> ExecutionRecord:
    import time

    start = time.perf_counter_ns()
    result = run_test(list(command))
    end = time.perf_counter_ns()
    elapsed_ms = (end - start) / 1_000_000.0
    return ExecutionRecord(variant, result.success, elapsed_ms, result.actual_moves, result.reported_moves, result.failure_reason)


def print_report_summary(config: ReportConfig, metrics_map: Dict[int, RunMetrics]) -> None:
    print()
    print("=== Statistical Summary ===")
    for variant_value in config.variants:
        print()
        print(f"Variant {variant_value} ({config.maps_per_variant} maps)")
        metrics = metrics_map.get(variant_value, RunMetrics())
        print_metrics_for_variant(metrics)


def print_metrics_for_variant(metrics: RunMetrics) -> None:
    if metrics.sample_size() == 0:
        print("No runs recorded.")
        return
    header = f"{pad_right('Metric', 28)} | Value"
    print(header)
    print(repeat("-", len(header)))
    print(format_metric_row("Runs", str(metrics.sample_size())))
    print(format_metric_row("Wins", f"{metrics.total_wins()} ({format_percentage(metrics.win_percentage())})"))
    print(format_metric_row("Losses", f"{metrics.total_losses()} ({format_percentage(metrics.loss_percentage())})"))
    execution = summarize_doubles(metrics.execution_times_ms)
    print_stats_block("Execution", execution, "ms")
    actual_moves = summarize_integers(metrics.actual_moves)
    print_stats_block("Actual moves", actual_moves, None)
    if metrics.reported_moves:
        reported_moves = summarize_integers(metrics.reported_moves)
        print_stats_block("Reported moves", reported_moves, None)
    failure_summary = top_failure_summary(metrics)
    if failure_summary:
        print(format_metric_row("Top failures", failure_summary))


def print_stats_block(base_label: str, summary: StatsSummary, unit: Optional[str]) -> None:
    suffix = "" if not unit else f" {unit}"
    print(format_metric_row(f"{base_label} mean", append_unit(format_number(summary.mean), suffix)))
    print(format_metric_row(f"{base_label} median", append_unit(format_number(summary.median), suffix)))
    print(format_metric_row(f"{base_label} mode", append_unit(format_number(summary.mode), suffix)))
    print(format_metric_row(f"{base_label} std dev", append_unit(format_number(summary.std_dev), suffix)))


def append_unit(value: str, unit_suffix: str) -> str:
    if not unit_suffix or value == "-":
        return value
    return f"{value}{unit_suffix}"


def format_metric_row(label: str, value: str) -> str:
    return f"{pad_right(label, 28)} | {value}"


def command_to_display(command: Sequence[str]) -> str:
    if not command:
        return "(empty)"
    result = []
    for token in command:
        if " " in token:
            escaped = token.replace('"', r'\"')
            result.append(f"\"{escaped}\"")
        else:
            result.append(token)
    return " ".join(result)


def top_failure_summary(metrics: RunMetrics) -> Optional[str]:
    if not metrics.failure_reasons:
        return None
    counts = Counter(metrics.failure_reasons)
    items = counts.most_common()
    items.sort(key=lambda item: (-item[1], item[0]))
    top = items[:2]
    return "; ".join(f"{count}× {reason}" for reason, count in top)


def summarize_doubles(values: Sequence[float]) -> StatsSummary:
    if not values:
        return StatsSummary()
    sorted_values = sorted(values)
    mean = sum(sorted_values) / len(sorted_values)
    size = len(sorted_values)
    if size % 2 == 0:
        median = (sorted_values[size // 2 - 1] + sorted_values[size // 2]) / 2.0
    else:
        median = sorted_values[size // 2]
    mode = compute_mode_for_doubles(sorted_values)
    variance = sum((value - mean) ** 2 for value in sorted_values) / size
    std_dev = math.sqrt(variance)
    return StatsSummary(mean, median, mode, std_dev)


def summarize_integers(values: Sequence[int]) -> StatsSummary:
    if not values:
        return StatsSummary()
    sorted_values = sorted(values)
    mean = sum(sorted_values) / len(sorted_values)
    size = len(sorted_values)
    if size % 2 == 0:
        median = (sorted_values[size // 2 - 1] + sorted_values[size // 2]) / 2.0
    else:
        median = sorted_values[size // 2]
    mode = compute_mode_for_integers(sorted_values)
    variance = sum((value - mean) ** 2 for value in sorted_values) / size
    std_dev = math.sqrt(variance)
    return StatsSummary(mean, median, mode, std_dev)


def compute_mode_for_doubles(values: Sequence[float]) -> float:
    if not values:
        return float("nan")
    counts: Dict[float, int] = defaultdict(int)
    mode = float("nan")
    max_count = -1
    for value in values:
        key = round(value * 10.0) / 10.0
        counts[key] += 1
        count = counts[key]
        if count > max_count or (count == max_count and (math.isnan(mode) or key < mode)):
            max_count = count
            mode = key
    return mode


def compute_mode_for_integers(values: Sequence[int]) -> float:
    if not values:
        return float("nan")
    counts: Dict[int, int] = defaultdict(int)
    mode = 0
    max_count = -1
    for value in values:
        counts[value] += 1
        count = counts[value]
        if count > max_count or (count == max_count and value < mode):
            max_count = count
            mode = value
    return float(mode)


def pad_right(value: Optional[str], width: int) -> str:
    if value is None:
        value = ""
    if len(value) >= width:
        return value
    return value + " " * (width - len(value))


def repeat(value: str, count: int) -> str:
    return value * count


def format_number(value: float) -> str:
    if math.isnan(value) or math.isinf(value):
        return "-"
    return f"{value:.2f}"


def format_percentage(value: float) -> str:
    if math.isnan(value) or math.isinf(value):
        return "-"
    return f"{value:.2f}%"


def generate_test() -> None:
    global grid, variant, safe_placement_distance, enemies, frodo_x, frodo_y
    global ring_on, has_mithril, found_gollum, mithril_x, mithril_y
    global gollum_x, gollum_y, mount_doom_x, mount_doom_y

    variant = active_difficulty.random_vision(rand)
    safe_placement_distance = active_difficulty.safe_distance()
    grid = [["." for _ in range(GRID_SIZE)] for _ in range(GRID_SIZE)]
    enemies = []
    frodo_x = 0
    frodo_y = 0
    ring_on = False
    has_mithril = False
    found_gollum = False
    mithril_x = -1
    mithril_y = -1

    grid[0][0] = "S"

    if active_difficulty == Difficulty.EASY:
        setup_easy_scenario()
    elif active_difficulty == Difficulty.NORMAL:
        setup_normal_scenario()
    else:
        setup_hard_scenario()

    log(f"Difficulty: {active_difficulty}")
    log(f"Variant: {variant}")
    log(f"Gollum: ({gollum_x}, {gollum_y})")
    log(f"Mount Doom: ({mount_doom_x}, {mount_doom_y})")
    if mithril_x >= 0:
        log(f"Mithril: ({mithril_x}, {mithril_y})")
    log(f"Enemies: {len(enemies)}")
    if dump_map:
        print_grid()


def run_test(command: List[str]) -> TestResult:
    reset_simulation_state()
    process = subprocess.Popen(
        command,
        cwd=os.getcwd(),
        stdin=subprocess.PIPE,
        stdout=subprocess.PIPE,
        stderr=subprocess.PIPE,
        text=True,
        encoding="utf-8",
    )

    try:
        reader = process.stdout
        writer = process.stdin
        assert reader is not None and writer is not None

        writer.write(f"{variant}\n")
        writer.write(f"{gollum_x} {gollum_y}\n")
        writer.flush()

        surroundings = get_surroundings(0, 0)
        writer.write(f"{len(surroundings)}\n")
        for entry in surroundings:
            writer.write(f"{entry}\n")
        writer.flush()

        actual_moves = 0

        while True:
            line = reader.readline()
            if not line:
                reason = "ERROR: Unexpected end of stream"
                log(reason)
                return TestResult.failure_result(reason, actual_moves, -1)

            command_line = line.strip()
            if not command_line:
                continue

            parts = command_line.split()
            op = parts[0]

            if op == "m":
                if len(parts) < 3:
                    reason = f"ERROR: Malformed move command: '{command_line}'"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)
                try:
                    x = int(parts[1])
                    y = int(parts[2])
                except ValueError:
                    reason = f"ERROR: Invalid move coordinates: '{command_line}'"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)

                if not is_valid_move(x, y):
                    reason = f"ERROR: Invalid move ({frodo_x},{frodo_y}) -> ({x},{y})"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)

                set_frodo_position(x, y)
                actual_moves += 1

                if is_dead():
                    reason = f"ERROR: Frodo died at ({frodo_x},{frodo_y})"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)

                if grid[frodo_x][frodo_y] == "C":
                    set_has_mithril(True)

                surroundings = get_surroundings(frodo_x, frodo_y)
                writer.write(f"{len(surroundings)}\n")
                for entry in surroundings:
                    writer.write(f"{entry}\n")

                if frodo_x == gollum_x and frodo_y == gollum_y and not found_gollum:
                    set_found_gollum(True)
                    writer.write(f"My precious! Mount Doom is {mount_doom_x} {mount_doom_y}\n")

                writer.flush()
            elif op == "r":
                if ring_on:
                    reason = "ERROR: Ring already on"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)
                set_ring_state(True)
                surroundings = get_surroundings(frodo_x, frodo_y)
                writer.write(f"{len(surroundings)}\n")
                for entry in surroundings:
                    writer.write(f"{entry}\n")
                writer.flush()
            elif op == "rr":
                if not ring_on:
                    reason = "ERROR: Ring already off"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)
                set_ring_state(False)
                surroundings = get_surroundings(frodo_x, frodo_y)
                writer.write(f"{len(surroundings)}\n")
                for entry in surroundings:
                    writer.write(f"{entry}\n")
                writer.flush()
            elif op == "e":
                if len(parts) < 2:
                    reason = f"ERROR: Malformed end command: '{command_line}'"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)
                try:
                    reported = int(parts[1])
                except ValueError:
                    reason = f"ERROR: Invalid end command payload: '{command_line}'"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, -1)

                if reported == -1:
                    log("Solution reported: NO PATH")
                    return TestResult.success_result(actual_moves, reported)

                if frodo_x != mount_doom_x or frodo_y != mount_doom_y:
                    reason = f"ERROR: Not at Mount Doom! At ({frodo_x},{frodo_y})"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, reported)

                if not found_gollum:
                    reason = "ERROR: Didn't find Gollum"
                    log(reason)
                    return TestResult.failure_result(reason, actual_moves, reported)

                log(f"Moves: {actual_moves} (reported: {reported})")

                if actual_moves == reported:
                    return TestResult.success_result(actual_moves, reported)
                reason = "ERROR: Wrong move count"
                log(reason)
                return TestResult.failure_result(reason, actual_moves, reported)
            else:
                reason = f"ERROR: Unknown command '{op}'"
                log(reason)
                return TestResult.failure_result(reason, actual_moves, -1)
    finally:
        try:
            process.terminate()
        except OSError:
            pass
        try:
            process.wait(timeout=1)
        except subprocess.TimeoutExpired:
            process.kill()


def place_enemy(enemy_type: str, predicate: Optional[Callable[[int, int], bool]] = None) -> None:
    global enemies
    if predicate is None:
        pos = find_empty_cell()
    else:
        pos = find_cell(predicate)
    enemy = Enemy(pos[0], pos[1], enemy_type)
    enemies.append(enemy)
    grid[pos[0]][pos[1]] = enemy_type


def setup_easy_scenario() -> None:
    orc_count = rand.randint(1, 2)
    for _ in range(orc_count):
        place_enemy("O")
    place_enemy("U")
    if rand.random() < 0.5:
        place_enemy("N")
    place_enemy("W")
    if rand.random() < 0.5:
        pos = find_safe_cell()
        place_mithril(pos)
    g_pos = find_safe_cell()
    place_gollum(g_pos)
    d_pos = find_safe_cell()
    place_mount_doom(d_pos)


def setup_normal_scenario() -> None:
    orc_count = 3 + rand.randint(0, 1)
    for _ in range(orc_count):
        place_enemy("O")
    place_enemy("U")
    place_enemy("U", lambda x, y: distance_from_start(x, y) <= 6)
    place_enemy("N")
    place_enemy("W")
    place_enemy("W", lambda x, y: has_enemy_within(x, y, 2))
    mithril_pos = find_cell(lambda x, y: is_safe_for_placement(x, y) and distance_from_start(x, y) >= 6)
    place_mithril(mithril_pos)
    g_pos = find_cell(lambda x, y: is_safe_for_placement(x, y) and distance_from_start(x, y) >= 4)
    place_gollum(g_pos)
    d_pos = find_cell(lambda x, y: is_safe_for_placement(x, y) and distance_from_start(x, y) >= 7)
    place_mount_doom(d_pos)


def setup_hard_scenario() -> None:
    orc_count = 4 + rand.randint(0, 2)
    for _ in range(orc_count):
        place_enemy("O", lambda x, y: distance_from_start(x, y) <= 6)
    place_enemy("U", lambda x, y: distance_from_start(x, y) <= 5)
    place_enemy("U", lambda x, y: has_enemy_within(x, y, 1))
    place_enemy("N")
    place_enemy("N", lambda x, y: distance_from_start(x, y) <= 6)
    place_enemy("W")
    place_enemy("W", lambda x, y: has_enemy_within(x, y, 2))
    mithril_pos = find_cell(lambda x, y: grid[x][y] == "." and has_enemy_within(x, y, 1))
    place_mithril(mithril_pos)
    g_pos = find_cell(lambda x, y: grid[x][y] == "." and has_enemy_within(x, y, 1))
    place_gollum(g_pos)
    d_pos = find_cell(lambda x, y: grid[x][y] == "." and has_enemy_within(x, y, 2) and distance_from_start(x, y) >= 6)
    place_mount_doom(d_pos)


def place_mithril(pos: Tuple[int, int]) -> None:
    global mithril_x, mithril_y
    mithril_x, mithril_y = pos
    grid[mithril_x][mithril_y] = "C"


def place_gollum(pos: Tuple[int, int]) -> None:
    global gollum_x, gollum_y
    gollum_x, gollum_y = pos
    grid[gollum_x][gollum_y] = "G"


def place_mount_doom(pos: Tuple[int, int]) -> None:
    global mount_doom_x, mount_doom_y
    mount_doom_x, mount_doom_y = pos
    grid[mount_doom_x][mount_doom_y] = "M"


def find_empty_cell() -> Tuple[int, int]:
    while True:
        x = rand.randint(0, GRID_SIZE - 1)
        y = rand.randint(0, GRID_SIZE - 1)
        if grid[x][y] == ".":
            return x, y


def find_safe_cell(min_distance: Optional[int] = None) -> Tuple[int, int]:
    if min_distance is None:
        min_distance = safe_placement_distance
    for _ in range(1000):
        x = rand.randint(0, GRID_SIZE - 1)
        y = rand.randint(0, GRID_SIZE - 1)
        if is_safe_for_placement(x, y, min_distance):
            return x, y
    return find_empty_cell()


def is_safe_for_placement(x: int, y: int, min_distance: Optional[int] = None) -> bool:
    if min_distance is None:
        min_distance = safe_placement_distance
    if grid[x][y] != ".":
        return False
    for enemy in enemies:
        if abs(x - enemy.x) + abs(y - enemy.y) <= min_distance:
            return False
    return True


def is_valid_move(x: int, y: int) -> bool:
    if x < 0 or x >= GRID_SIZE or y < 0 or y >= GRID_SIZE:
        return False
    dx = abs(x - frodo_x)
    dy = abs(y - frodo_y)
    return (dx == 1 and dy == 0) or (dx == 0 and dy == 1)


def is_dead() -> bool:
    for enemy in enemies:
        if is_in_lethal_zone(frodo_x, frodo_y, enemy):
            return True
    return False


def is_in_lethal_zone(x: int, y: int, enemy: Enemy) -> bool:
    if x == enemy.x and y == enemy.y:
        return True
    dist = abs(x - enemy.x) + abs(y - enemy.y)
    cheb_dist = max(abs(x - enemy.x), abs(y - enemy.y))
    if enemy.type == "O":
        if ring_on or has_mithril:
            return False
        return dist <= 1
    if enemy.type == "U":
        if ring_on or has_mithril:
            return dist <= 1
        return dist <= 2
    if enemy.type == "N":
        if has_mithril:
            return cheb_dist <= 1
        if ring_on:
            return 0 < cheb_dist <= 2
        return cheb_dist <= 1
    if enemy.type == "W":
        return cheb_dist <= 2
    return False


def get_surroundings(x: int, y: int) -> List[str]:
    visible: List[str] = []
    radius = variant
    for dx in range(-radius, radius + 1):
        for dy in range(-radius, radius + 1):
            if dx == 0 and dy == 0:
                continue
            nx = x + dx
            ny = y + dy
            if nx < 0 or nx >= GRID_SIZE or ny < 0 or ny >= GRID_SIZE:
                continue
            cell = grid[nx][ny]
            if cell == "G" and not found_gollum:
                visible.append(f"{nx} {ny}G")
            elif cell == "M" and found_gollum:
                visible.append(f"{nx} {ny}M")
            elif cell == "C" and nx == mithril_x and ny == mithril_y and not has_mithril:
                visible.append(f"{nx}{ny}C")
            else:
                for enemy in enemies:
                    if enemy.x == nx and enemy.y == ny:
                        visible.append(f"{nx} {ny}{enemy.type}")
                        break
                    if is_in_perception_zone(nx, ny, enemy):
                        visible.append(f"{nx} {ny}P")
                        break
    return visible


def is_in_perception_zone(x: int, y: int, enemy: Enemy) -> bool:
    if x == enemy.x and y == enemy.y:
        return False
    dist = abs(x - enemy.x) + abs(y - enemy.y)
    cheb_dist = max(abs(x - enemy.x), abs(y - enemy.y))
    if enemy.type == "O":
        if ring_on or has_mithril:
            return False
        return dist == 1
    if enemy.type == "U":
        if ring_on or has_mithril:
            if dist == 1:
                return False
            return dist == 2
        return 0 < dist <= 2
    if enemy.type == "N":
        if has_mithril:
            return cheb_dist == 1
        if ring_on:
            return 0 < cheb_dist <= 2
        return cheb_dist == 1
    if enemy.type == "W":
        return 0 < cheb_dist <= 2
    return False


def find_cell(predicate: Callable[[int, int], bool]) -> Tuple[int, int]:
    for _ in range(2000):
        x = rand.randint(0, GRID_SIZE - 1)
        y = rand.randint(0, GRID_SIZE - 1)
        if grid[x][y] == "." and predicate(x, y):
            return x, y
    return find_empty_cell()


def has_enemy_within(x: int, y: int, radius: int) -> bool:
    for enemy in enemies:
        if abs(x - enemy.x) + abs(y - enemy.y) <= radius:
            return True
    return False


def distance_from_start(x: int, y: int) -> int:
    return abs(x) + abs(y)


def try_parse_int(value: str) -> Optional[int]:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def try_parse_long(value: str) -> Optional[int]:
    try:
        return int(value)
    except (TypeError, ValueError):
        return None


def is_random_keyword(value: str) -> bool:
    normalized = value.strip().lower()
    return normalized in {"random", "mixed"}


def is_map_dump_keyword(value: str) -> bool:
    normalized = value.strip().lower()
    return normalized in {"map", "dump", "--map", "--dump"}


def is_report_keyword(value: str) -> bool:
    if value is None:
        return False
    normalized = value.strip().lower()
    return normalized in {"report", "--report"}


def print_grid() -> None:
    if not verbose_output:
        return
    print("Grid:")
    header = "    " + "  ".join(f"{col}" if col < 10 else f"{col}" for col in range(GRID_SIZE))
    print(header)
    for row in range(GRID_SIZE):
        row_cells = " ".join(grid[row][col] for col in range(GRID_SIZE))
        print(f"{row:2d} {row_cells}")


def set_active_difficulty(value: Difficulty) -> None:
    global active_difficulty
    active_difficulty = value


def set_frodo_position(x: int, y: int) -> None:
    global frodo_x, frodo_y
    frodo_x, frodo_y = x, y


def set_ring_state(on: bool) -> None:
    global ring_on
    ring_on = on


def set_has_mithril(value: bool) -> None:
    global has_mithril
    has_mithril = value


def set_found_gollum(value: bool) -> None:
    global found_gollum
    found_gollum = value


if __name__ == "__main__":
    main(sys.argv[1:])
