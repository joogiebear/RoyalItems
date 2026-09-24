"""Prepare a calendar-versioned release without creating source commits."""
import datetime
import os
import re
import subprocess
from pathlib import Path
from zoneinfo import ZoneInfo


def git(*args):
    return subprocess.check_output(["git", *args], text=True).strip()


def next_version(tags, today):
    iso = today.isocalendar()   # its year, not the calendar year: 1 Jan 2027 is 2026.53
    prefix = f"{iso.year}.{iso.week}."
    patches = [int(tag[len(prefix):]) for tag in tags
               if re.fullmatch(re.escape(prefix) + r"\d+", tag)]
    return prefix + str(max(patches, default=-1) + 1)


def prepare():
    today = datetime.datetime.now(ZoneInfo("America/Chicago")).date()
    tags = git("tag", "--merged", "HEAD").splitlines()
    release_tags = [tag for tag in tags if re.fullmatch(r"\d{4}\.\d+\.\d+|v0\.\d+\.\d+", tag)]
    tagged_commits = {git("rev-list", "-1", tag): tag for tag in release_tags}
    # --first-parent chooses the latest release on this branch, not on an old side branch.
    last = next((tagged_commits[commit] for commit in git("rev-list", "--first-parent", "HEAD").splitlines()
                 if commit in tagged_commits), None)
    commit_range = f"{last}..HEAD" if last else "HEAD"
    changes = [line for line in git("log", commit_range, "--no-merges", "--format=%s").splitlines()
               if not line.startswith("chore(release):")]
    if not changes and os.environ.get("FORCE_RELEASE") != "true":
        return {"release": "false"}
    version = next_version(git("tag").splitlines(), today)
    notes = f"## {version} — {today}\n\n" + "\n".join(f"- {change}" for change in changes)
    notes += "\n\nInstall the JAR in `plugins/` and restart the server.\n"
    Path("RELEASE_NOTES.md").write_text(notes, encoding="utf-8")
    return {"release": "true", "version": version}


if __name__ == "__main__":
    with open(os.environ["GITHUB_OUTPUT"], "a", encoding="utf-8") as output:
        for key, value in prepare().items():
            print(f"{key}={value}", file=output)
