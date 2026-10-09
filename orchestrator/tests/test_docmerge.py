"""Row merge of the shared documents sibling Lanes append to (#77)."""

from orchestrator.docmerge import merge_rows


def conflict(ours: str, base: str, theirs: str) -> str:
    return (
        "# Plan\n\n| Ticket | Check | Status |\n|---|---|---|\n"
        f"<<<<<<< HEAD\n{ours}||||||| base\n{base}=======\n{theirs}>>>>>>> origin/main\n"
        "\nAfter the table.\n"
    )


def test_rows_added_on_both_sides_are_all_kept() -> None:
    merged = merge_rows(conflict("| #2 | lane | ☐ |\n", "", "| #1 | main | ☐ |\n"))

    assert merged is not None and "<<<<<<<" not in merged
    assert merged.index("| #1 | main") < merged.index("| #2 | lane")
    assert merged.endswith("\nAfter the table.\n")


def test_a_row_one_side_changed_takes_that_side_and_new_rows_are_kept() -> None:
    base = "| #1 | store | ☐ |\n"
    merged = merge_rows(conflict(base + "| #2 | lane | ☐ |\n", base, "| #1 | store | ☑ #9 |\n"))

    assert merged is not None
    assert "| #1 | store | ☑ #9 |" in merged and "| #1 | store | ☐ |" not in merged
    assert "| #2 | lane | ☐ |" in merged


def test_a_row_removed_on_one_side_stays_removed() -> None:
    base = "| #1 | old | ☐ |\n| #2 | keep | ☐ |\n"
    merged = merge_rows(conflict("| #2 | keep | ☐ |\n| #3 | new | ☐ |\n", base, base))

    assert merged is not None and "| #1 | old" not in merged and "| #3 | new" in merged


def test_a_row_both_sides_changed_differently_is_left_for_a_human() -> None:
    base = "| #1 | store | ☐ |\n"

    assert merge_rows(conflict("| #1 | store | ☑ #8 |\n", base, "| #1 | store | ☑ #9 |\n")) is None


def test_a_conflict_without_the_base_section_is_left_for_a_human() -> None:
    text = "<<<<<<< HEAD\n| #2 | a | ☐ |\n=======\n| #1 | b | ☐ |\n>>>>>>> origin/main\n"

    assert merge_rows(text) is None
