"""Running Exit Gate commands, and temporary service instances for checks needing a running app."""

import os
import shutil
import socket
import subprocess
import tempfile
from dataclasses import dataclass
from pathlib import Path

OUTPUT_TAIL = 4000
# Signs that a command couldn't run on this machine at all, so no code change can fix it: shells
# exit 126 (not executable) or 127 (not found), and these messages come from missing runtimes.
ENVIRONMENT_EXIT_CODES = (126, 127)
ENVIRONMENT_MESSAGES = ("Unable to locate a Java Runtime", "JAVA_HOME is not", "command not found")


@dataclass(frozen=True)
class Outcome:
    command: str
    ok: bool
    output: str
    returncode: int = 0

    def problem(self) -> str:
        return f"`{self.command}` failed:\n{self.output}"

    @property
    def environment(self) -> bool:
        """The command couldn't run here (a missing tool or runtime), as opposed to failing."""
        return not self.ok and (
            self.returncode in ENVIRONMENT_EXIT_CODES
            or any(m in self.output for m in ENVIRONMENT_MESSAGES)
        )


def run(command: str, cwd: Path, env: dict[str, str] | None = None) -> Outcome:
    result = subprocess.run(
        command,
        shell=True,
        cwd=cwd,
        env={**os.environ, **(env or {})},
        capture_output=True,
        text=True,
    )
    output = (result.stdout + result.stderr)[-OUTPUT_TAIL:]
    return Outcome(command, result.returncode == 0, output, result.returncode)


def free_port() -> int:
    with socket.socket() as s:
        s.bind(("127.0.0.1", 0))
        return int(s.getsockname()[1])


def with_temporary_instance(start: str, check: str, stop: str, cwd: Path) -> Outcome:
    """Start the service from `cwd` on a free port and a fresh data directory, run `check` against
    it, and always stop it and delete the data directory. A running service is never touched."""
    port = free_port()
    data_dir = tempfile.mkdtemp(prefix="orchestrator-")
    env = {"PORT": str(port), "DATA_DIR": data_dir, "BASE_URL": f"http://localhost:{port}"}
    try:
        started = run(start, cwd, env)
        if not started.ok:
            return started
        return run(check, cwd, env)
    finally:
        run(stop, cwd, env)
        shutil.rmtree(data_dir, ignore_errors=True)
