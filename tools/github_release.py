"""Publish this project's public repository / APK previews without storing credentials.

Authentication: GH_TOKEN environment variable or the configured Git credential helper.
Only public result URLs are printed. Never put a token into a Git remote URL.
"""
import argparse
import json
import os
from pathlib import Path
import subprocess
import sys
import urllib.error
import urllib.parse
import urllib.request


def token():
    value = os.environ.get("GH_TOKEN") or os.environ.get("GITHUB_TOKEN")
    if value:
        return value
    env = {**os.environ, "GIT_TERMINAL_PROMPT": "0", "GCM_INTERACTIVE": "never"}
    result = subprocess.run(["git", "credential", "fill"], input="protocol=https\nhost=github.com\n\n",
                            text=True, capture_output=True, env=env)
    fields = dict(line.split("=", 1) for line in result.stdout.splitlines() if "=" in line)
    if result.returncode or not fields.get("password"):
        raise RuntimeError("GitHub authentication is unavailable; authenticate Git or set GH_TOKEN locally.")
    return fields["password"]


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("action", choices=["check", "create", "release", "runs"])
    parser.add_argument("--owner", required=True)
    parser.add_argument("--repo", default="chitalka")
    parser.add_argument("--tag")
    parser.add_argument("--apk", type=Path)
    parser.add_argument("--notes", type=Path)
    args = parser.parse_args()
    credential = token()

    def request(method, path, data=None, raw=None):
        url = path if path.startswith("https://") else "https://api.github.com" + path
        parsed = urllib.parse.urlparse(url)
        if parsed.scheme != "https" or parsed.hostname not in {"api.github.com", "uploads.github.com"}:
            raise RuntimeError("Unexpected GitHub API host")
        headers = {"Authorization": "Bearer " + credential, "Accept": "application/vnd.github+json",
                   "X-GitHub-Api-Version": "2022-11-28", "User-Agent": "Chitalka-release-tool"}
        body = raw
        if data is not None:
            body = json.dumps(data).encode()
            headers["Content-Type"] = "application/json"
        elif raw is not None:
            headers["Content-Type"] = "application/vnd.android.package-archive"
        req = urllib.request.Request(url, data=body, headers=headers, method=method)
        with urllib.request.urlopen(req, timeout=180) as response:
            return json.load(response)

    profile = request("GET", "/user")
    if profile["login"].lower() != args.owner.lower():
        raise RuntimeError("Authenticated GitHub account does not match --owner")
    base = f"/repos/{args.owner}/{args.repo}"
    if args.action == "check":
        print(json.dumps({"login": profile["login"]}))
    elif args.action == "create":
        try:
            repo = request("GET", base)
            if repo.get("private"):
                raise RuntimeError("A private repository already uses this name; it will not be exposed or modified.")
            print(json.dumps({"url": repo["html_url"], "already_exists": True}))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            repo = request("POST", "/user/repos", {
                "name": args.repo, "private": False, "auto_init": False,
                "description": "Android EPUB reader with English–Russian translation, vocabulary practice and quote bookmarks.",
                "has_issues": True, "has_wiki": False,
            })
            print(json.dumps({"url": repo["html_url"], "already_exists": False}))
    elif args.action == "release":
        if not args.tag or not args.apk or not args.notes:
            parser.error("release requires --tag, --apk and --notes")
        commit = subprocess.check_output(["git", "rev-parse", "HEAD"], text=True).strip()
        try:
            release = request("GET", base + "/releases/tags/" + urllib.parse.quote(args.tag, safe=""))
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            release = request("POST", base + "/releases", {
                "tag_name": args.tag, "target_commitish": commit,
                "name": "Читалка " + args.tag.removeprefix("v"),
                "body": args.notes.read_text(encoding="utf-8"), "prerelease": True, "draft": False,
            })
        name = args.apk.name
        if any(asset["name"] == name for asset in release.get("assets", [])):
            raise RuntimeError("An asset with this name already exists; use a new version instead of replacing it.")
        upload = release["upload_url"].split("{", 1)[0] + "?" + urllib.parse.urlencode({"name": name})
        asset = request("POST", upload, raw=args.apk.read_bytes())
        print(json.dumps({"release": release["html_url"], "apk": asset["browser_download_url"]}))
    else:
        runs = request("GET", base + "/actions/runs?per_page=3")
        print(json.dumps([{"id": run["id"], "url": run["html_url"], "status": run["status"],
                           "conclusion": run["conclusion"], "sha": run["head_sha"]}
                          for run in runs["workflow_runs"]]))


if __name__ == "__main__":
    try:
        main()
    except urllib.error.HTTPError as error:
        print(f"GitHub API request failed: HTTP {error.code} {error.reason}", file=sys.stderr)
        sys.exit(1)
    except (RuntimeError, OSError) as error:
        print(str(error), file=sys.stderr)
        sys.exit(1)
