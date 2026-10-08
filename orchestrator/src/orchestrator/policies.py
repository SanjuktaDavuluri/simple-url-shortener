"""Policy guardrails (ADR 0010): one pure check, used before each agent action and after each step.

`decide` judges a proposed tool call; `check_changes` judges what a step actually changed. Neither
ever repeats a matched credential in its reasons.
"""

import re
import shlex
from dataclasses import dataclass, field
from pathlib import Path, PurePosixPath
from typing import Any
from urllib.parse import urlparse

import yaml

from orchestrator.hashing import content_hash

POLICY_FILE = "orchestrator/policies.yaml"
OPERATORS = {";", "&&", "||", "|", "&", "\n", "|&"}
WRAPPERS = {"env", "command", "exec", "nohup", "time", "nice", "builtin"}
URL = re.compile(r"https?://[^\s'\"<>|;)]+")
SCHEMA: dict[str, type] = {
    "version": int,
    "git": dict,
    "protected_paths": list,
    "stage_paths": dict,
    "dependency_manifests": list,
    "commands": dict,
    "network": dict,
    "secrets": dict,
}


class PolicyError(Exception):
    pass


@dataclass(frozen=True)
class Policies:
    raw: dict[str, Any]
    digest: str
    secrets: dict[str, re.Pattern[str]] = field(default_factory=dict)

    @property
    def run_branch(self) -> re.Pattern[str]:
        return re.compile(self.raw["git"]["run_branch_pattern"])


@dataclass(frozen=True)
class ToolCall:
    tool: str  # Bash | Write | Edit | Read | WebFetch | …
    input: dict[str, Any]


@dataclass(frozen=True)
class Decision:
    allowed: bool
    rule: str = ""
    reason: str = ""


ALLOW = Decision(True)


@dataclass(frozen=True)
class Change:
    path: str
    added: str  # the lines this step added


def parse(text: str) -> Policies:
    raw = yaml.safe_load(text)
    if not isinstance(raw, dict):
        raise PolicyError("the policy file must be a mapping")
    missing = [key for key in SCHEMA if key not in raw]
    unknown = [key for key in raw if key not in SCHEMA]
    wrong = [key for key, kind in SCHEMA.items() if key in raw and not isinstance(raw[key], kind)]
    problems = (
        [f"missing: {', '.join(missing)}"] * bool(missing)
        + [f"unknown: {', '.join(unknown)}"] * bool(unknown)
        + [f"wrong type: {', '.join(wrong)}"] * bool(wrong)
    )
    if not problems:
        needed = {
            "git": ["protected_branches", "run_branch_pattern"],
            "commands": [
                "blocked_programs",
                "blocked_gh",
                "read_only_api",
                "downloaders",
                "interpreters",
            ],
            "network": ["allowed_hosts"],
        }
        problems += [
            f"missing: {section}.{key}"
            for section, keys in needed.items()
            for key in keys
            if key not in raw[section]
        ]
    if problems:
        raise PolicyError("; ".join(problems))
    try:
        secrets = {name: re.compile(pattern) for name, pattern in raw["secrets"].items()}
        re.compile(raw["git"]["run_branch_pattern"])
    except re.error as e:
        raise PolicyError(f"invalid pattern: {e}") from e
    return Policies(raw=raw, digest=content_hash(text), secrets=secrets)


def load_policies(repo_root: Path) -> Policies:
    path = repo_root / POLICY_FILE
    if not path.is_file():
        raise PolicyError(f"{POLICY_FILE} is required")
    try:
        return parse(path.read_text())
    except PolicyError as e:
        raise PolicyError(f"{POLICY_FILE}: {e}") from e


# Paths


def _relative(path: str, workspace: Path) -> str | None:
    """The path relative to the workspace, or None when it points outside it."""
    p = PurePosixPath(path)
    base = PurePosixPath(workspace.as_posix())
    if p.is_absolute():
        try:
            p = p.relative_to(base)
        except ValueError:
            return None
    parts: list[str] = []
    for part in p.parts:
        if part == "..":
            if not parts:
                return None
            parts.pop()
        elif part not in (".", ""):
            parts.append(part)
    return "/".join(parts)


def _path_rule(policies: Policies, stage: str, rel: str) -> Decision:
    for protected in policies.raw["protected_paths"]:
        if rel == protected.rstrip("/") or rel.startswith(
            protected if protected.endswith("/") else f"{protected}/"
        ):
            return Decision(
                False, "protected_paths", f"{rel} is protected: agents may not change it"
            )
    for prefix, stages in policies.raw["stage_paths"].items():
        if rel.startswith(prefix) and stage not in stages:
            return Decision(
                False, "stage_paths", f"{rel} may only be written in the {', '.join(stages)} Stage"
            )
    return ALLOW


def secret_names(policies: Policies, text: str) -> list[str]:
    return [name for name, pattern in policies.secrets.items() if pattern.search(text)]


# Shell commands


def _segments(command: str) -> list[list[str]]:
    lexer = shlex.shlex(command, posix=True, punctuation_chars=";&|")
    lexer.whitespace_split = True
    lexer.commenters = ""
    segments: list[list[str]] = [[]]
    try:
        for token in lexer:
            if token in OPERATORS:
                segments.append([])
            else:
                segments[-1].append(token)
    except ValueError:  # unbalanced quotes: judge the raw words
        segments = [command.split()]
    return [s for s in segments if s]


def _program(words: list[str]) -> tuple[str, list[str]]:
    """Strip variable assignments and wrappers; return the program's base name and its arguments."""
    i = 0
    while i < len(words):
        word = words[i]
        if re.match(r"^[A-Za-z_][A-Za-z0-9_]*=", word):
            i += 1
        elif word.lstrip("\\").rsplit("/", 1)[-1] in WRAPPERS:
            i += 1
            while i < len(words) and words[i].startswith("-"):
                i += 1
        else:
            break
    if i >= len(words):
        return "", []
    return words[i].lstrip("\\").rsplit("/", 1)[-1], words[i + 1 :]


def _git(policies: Policies, args: list[str]) -> Decision:
    while args and args[0] in ("-C", "-c", "--git-dir", "--work-tree"):
        args = args[2:]
    while args and args[0].startswith("-"):
        args = args[1:]
    if not args:
        return ALLOW
    sub, rest = args[0], args[1:]
    protected = set(policies.raw["git"]["protected_branches"])
    if sub == "push":
        force = {"-f", "--force", "--force-with-lease", "--force-if-includes", "--mirror"}
        if any(a in force or a.startswith("--force-with-lease=") for a in rest):
            return Decision(False, "git", "force-pushing is not allowed")
        deleting = any(a in ("-d", "--delete") for a in rest)
        refspecs = [a for a in rest if not a.startswith("-")][1:]
        for spec in refspecs:
            if spec.startswith("+"):
                return Decision(False, "git", "force-pushing (+refspec) is not allowed")
            src, _, dst = spec.rpartition(":") if ":" in spec else ("", "", spec)
            target = dst.removeprefix("refs/heads/")
            if (deleting or (":" in spec and not src)) and not policies.run_branch.match(target):
                return Decision(
                    False, "git", f"only the Run's own branches may be deleted, not {target}"
                )
            if target in protected or (target == "HEAD" and not src):
                return Decision(False, "git", f"pushing to {target} is not allowed; open a PR")
    if sub == "branch" and any(a in ("-d", "-D", "--delete") for a in rest):
        for name in (a for a in rest if not a.startswith("-")):
            if not policies.run_branch.match(name):
                return Decision(
                    False, "git", f"only the Run's own branches may be deleted, not {name}"
                )
    return ALLOW


def _gh(policies: Policies, args: list[str]) -> Decision:
    words: list[str] = []
    skip = False
    for a in args:
        if skip:
            skip = False
        elif a in ("-R", "--repo", "--hostname"):
            skip = True
        elif not a.startswith("-") or words[:1] == ["api"]:
            words.append(a)
    joined = " ".join(words)
    for blocked in policies.raw["commands"]["blocked_gh"]:
        if joined == blocked or joined.startswith(f"{blocked} "):
            return Decision(
                False, "commands", f"`gh {blocked}` is not allowed (humans merge and own settings)"
            )
    if words[:1] == ["api"]:
        method = "GET"
        for i, a in enumerate(args):
            if a in ("-X", "--method") and i + 1 < len(args):
                method = args[i + 1].upper()
            elif a.startswith("--method="):
                method = a.split("=", 1)[1].upper()
            elif a in ("-f", "-F", "--field", "--raw-field", "--input") and method == "GET":
                method = "POST"
        with_value = {
            "-X",
            "--method",
            "-f",
            "-F",
            "--field",
            "--raw-field",
            "-H",
            "--header",
            "--input",
            "-q",
            "--jq",
            "-t",
            "--template",
            "--cache",
            "-p",
            "--preview",
        }
        positional, skip_next = [], False
        for a in args[args.index("api") + 1 :]:
            if skip_next:
                skip_next = False
            elif a in with_value:
                skip_next = True
            elif not a.startswith("-"):
                positional.append(a)
        path = positional[0] if positional else ""
        if method != "GET" and any(
            re.search(p, path) for p in policies.raw["commands"]["read_only_api"]
        ):
            return Decision(False, "commands", f"`gh api` may not change {path}")
    return ALLOW


def _bash(policies: Policies, command: str, workspace: Path, depth: int = 0) -> Decision:
    cmds = policies.raw["commands"]
    for url in URL.findall(command):
        host = (urlparse(url).hostname or "").lower()
        if host not in policies.raw["network"]["allowed_hosts"]:
            return Decision(False, "network", f"{host} is not on the network allow-list")
    if re.search(r"<\(\s*(curl|wget)\b", command):
        return Decision(False, "commands", "running downloaded code is not allowed")
    segments = _segments(command)
    downloaded: set[str] = set()
    previous = ""
    for words in segments:
        program, args = _program(words)
        if program in cmds["blocked_programs"]:
            return Decision(False, "commands", f"`{program}` is not allowed")
        if program in cmds["interpreters"] and previous in cmds["downloaders"]:
            return Decision(
                False, "commands", "piping a download into an interpreter is not allowed"
            )
        if program in cmds["downloaders"]:
            for i, a in enumerate(args):
                if a in ("-o", "-O", "--output", "--output-document") and i + 1 < len(args):
                    downloaded.add(args[i + 1].removeprefix("./"))
        ran = args[0].removeprefix("./") if program in cmds["interpreters"] and args else ""
        if (ran and ran in downloaded) or words[0].removeprefix("./") in downloaded:
            return Decision(False, "commands", "running downloaded code is not allowed")
        if program in ("bash", "sh", "zsh", "dash") and "-c" in args and depth < 3:
            inner = args[args.index("-c") + 1] if args.index("-c") + 1 < len(args) else ""
            decision = _bash(policies, inner, workspace, depth + 1)
            if not decision.allowed:
                return decision
        if program == "eval" and depth < 3:
            decision = _bash(policies, " ".join(args), workspace, depth + 1)
            if not decision.allowed:
                return decision
        if program == "rm":
            for target in (a for a in args if not a.startswith("-")):
                if target.startswith("~") or _relative(target, workspace) in (None, ""):
                    return Decision(
                        False,
                        "commands",
                        f"deleting {target} (outside the workspace) is not allowed",
                    )
        if program == "git":
            decision = _git(policies, args)
            if not decision.allowed:
                return decision
        if program == "gh":
            decision = _gh(policies, args)
            if not decision.allowed:
                return decision
        previous = program
    return ALLOW


def decide(policies: Policies, stage: str, workspace: Path, call: ToolCall) -> Decision:
    """Before the action: may this agent step do this?"""
    if call.tool == "Bash":
        return _bash(policies, str(call.input.get("command", "")), workspace)
    if call.tool in ("Write", "Edit", "MultiEdit", "NotebookEdit"):
        path = str(call.input.get("file_path") or call.input.get("notebook_path") or "")
        rel = _relative(path, workspace)
        if rel is None:
            return Decision(False, "paths", f"{path} is outside the workspace")
        decision = _path_rule(policies, stage, rel)
        if not decision.allowed:
            return decision
        text = str(call.input.get("content") or call.input.get("new_string") or "")
        names = secret_names(policies, text)
        if names:
            return Decision(
                False, "secrets", f"{rel}: the content looks like a credential ({', '.join(names)})"
            )
        return ALLOW
    if call.tool == "WebFetch":
        host = (urlparse(str(call.input.get("url", ""))).hostname or "").lower()
        if host not in policies.raw["network"]["allowed_hosts"]:
            return Decision(False, "network", f"{host} is not on the network allow-list")
    return ALLOW


@dataclass(frozen=True)
class ChangeReview:
    problems: list[str]
    dependency_manifests: list[str]


def check_changes(policies: Policies, stage: str, changes: list[Change]) -> ChangeReview:
    """After the step: what did it actually change?"""
    problems: list[str] = []
    for change in changes:
        decision = _path_rule(policies, stage, change.path)
        if not decision.allowed:
            problems.append(decision.reason)
        for number, line in enumerate(change.added.splitlines(), start=1):
            names = secret_names(policies, line)
            if names:
                problems.append(
                    f"{change.path}: added line {number} looks like a credential "
                    f"({', '.join(names)}); remove it"
                )
    manifests = set(policies.raw["dependency_manifests"])
    return ChangeReview(problems, sorted(c.path for c in changes if c.path in manifests))
