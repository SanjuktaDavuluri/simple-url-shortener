"""The requirements Stage's Exit Gate: is the spec complete enough to put in front of a human?"""

import re

REQUIRED_SECTIONS = (
    "## Problem Statement",
    "## Solution",
    "## User Stories",
    "## Implementation Decisions",
    "## Testing Decisions",
    "## Out of Scope",
    "## Further Notes",
)
SPEC_PATH = re.compile(r"^docs/specs/\d{4}-[a-z0-9]+(?:-[a-z0-9]+)*\.md$")
USER_STORY = re.compile(r"^\s*\d+\.\s+As an? .+, I want .+, so that .+", re.MULTILINE)
FRONTMATTER_ROADMAP = re.compile(r"\A---\n(?:.*\n)*?roadmap:\s*(\S.*)\n(?:.*\n)*?---\n")


def _section(text: str, heading: str) -> str:
    start = text.find(f"\n{heading}\n")
    if start < 0:
        return ""
    rest = text[start + len(heading) + 2 :]
    end = rest.find("\n## ")
    return rest if end < 0 else rest[:end]


def check_spec(
    path: str, text: str | None, *, exists_on_main: bool, roadmap_item: str | None
) -> list[str]:
    """Return the problems found; an empty list means the gate passes."""
    if not SPEC_PATH.match(path):
        return [f"The spec must be a new file named docs/specs/NNNN-<slug>.md, not {path}"]
    if exists_on_main:
        return [f"{path} already exists on main; a new spec needs a new number"]
    if text is None:
        return [f"{path} was not written"]
    missing = [h for h in REQUIRED_SECTIONS if f"\n{h}\n" not in f"\n{text}"]
    problems = [f"Missing section: {h}" for h in missing]
    if "## User Stories" not in missing and not USER_STORY.search(
        _section(text, "## User Stories")
    ):
        problems.append(
            "User Stories needs numbered user stories: '1. As a <actor>, I want <feature>, "
            "so that <benefit>'"
        )
    if "## Testing Decisions" not in missing and not _section(text, "## Testing Decisions").strip():
        problems.append("Testing Decisions is empty")
    match = FRONTMATTER_ROADMAP.match(text)
    linked = match.group(1).strip() if match else None
    if roadmap_item and linked != roadmap_item:
        problems.append(
            f"The frontmatter must link the Run's roadmap item: roadmap: {roadmap_item}"
        )
    elif not linked:
        problems.append("The frontmatter must link a roadmap item: roadmap: R<n>")
    return problems
