"""Exercise the workflow's actual inline code without credentials or GitHub writes."""
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import yaml

ROOT = Path(__file__).resolve().parents[2]
RELEASE = yaml.load((ROOT / ".github/workflows/release.yml").read_text(), Loader=yaml.BaseLoader)
CI = yaml.load((ROOT / ".github/workflows/ci.yml").read_text(), Loader=yaml.BaseLoader)


def step(workflow, job, name):
    return next(s for s in workflow["jobs"][job]["steps"] if s["name"] == name)


def python_code(item):
    return item["run"].split("<<'PYTHON'\n", 1)[1].split("\nPYTHON", 1)[0]


PUBLISHER = python_code(step(RELEASE, "publish", "Verify bundle and create or resume the draft"))
PREPARE = python_code(step(RELEASE, "build", "Prepare versioned JAR, checksum, and source manifest"))
SCOPE = python_code(step(CI, "build-and-test", "Determine backend scope"))


class WorkflowContracts(unittest.TestCase):
    def test_build_tag_source_ancestry_and_versions(self):
        script = step(RELEASE, "build", "Validate tag, source, and versions")["run"]
        with tempfile.TemporaryDirectory() as directory:
            stub = Path(directory)
            git = stub / "git"
            git.write_text('''#!/bin/bash
case "$1" in
  rev-parse) if [[ "$2" == HEAD ]]; then echo "$TEST_HEAD"; else echo "$TEST_TAG"; fi ;;
  fetch) exit 0 ;;
  merge-base) exit "$TEST_ANCESTRY" ;;
  *) exit 99 ;;
esac
''')
            mvn = stub / "mvn"
            mvn.write_text('#!/bin/bash\necho "$TEST_VERSION"\n')
            git.chmod(0o700)
            mvn.chmod(0o700)
            env = dict(os.environ, PATH=f"{stub}:{os.environ['PATH']}",
                       GITHUB_REF_TYPE="tag", GITHUB_REF_NAME="v0.6.0", GITHUB_REF="refs/tags/v0.6.0",
                       GITHUB_SHA="a" * 40, GITHUB_OUTPUT=str(stub / "output"),
                       TEST_HEAD="a" * 40, TEST_TAG="a" * 40, TEST_ANCESTRY="0", TEST_VERSION="0.6.0")
            subprocess.run(["bash", "-e", "-c", script], env=env, check=True, capture_output=True)
            for key, value in (("GITHUB_REF_TYPE", "branch"), ("GITHUB_REF_NAME", "v0.6.0-rc1"),
                               ("TEST_HEAD", "b" * 40), ("TEST_TAG", "b" * 40),
                               ("TEST_ANCESTRY", "1"), ("TEST_VERSION", "0.6.0-SNAPSHOT")):
                with self.subTest(field=key):
                    result = subprocess.run(["bash", "-e", "-c", script], env=dict(env, **{key: value}), capture_output=True)
                    self.assertNotEqual(result.returncode, 0)

    def test_permissions_and_credential_isolation(self):
        self.assertEqual(RELEASE["permissions"], {})
        build, publish = RELEASE["jobs"]["build"], RELEASE["jobs"]["publish"]
        self.assertEqual(build["permissions"], {"contents": "read"})
        self.assertEqual(publish["permissions"], {"contents": "write"})
        self.assertEqual(publish["needs"], "build")
        for item in build["steps"]:
            self.assertNotIn("GH_TOKEN", item.get("env", {}))
            if item.get("uses", "").startswith("actions/checkout@"):
                self.assertEqual(item["with"]["persist-credentials"], "false")
        for item in publish["steps"]:
            self.assertNotIn("checkout", item.get("uses", ""))
            self.assertNotRegex(item.get("run", ""), r"(?m)^\s*(mvn|java|bash|sh)\s")
        download = step(RELEASE, "publish", "Download the verified release bundle")["with"]
        self.assertEqual(download["artifact-ids"], "${{ needs.build.outputs.artifact_id }}")
        self.assertNotIn("run-id", download)
        self.assertEqual(download["merge-multiple"], "true")
        self.assertEqual(download["path"], "${{ runner.temp }}/release-assets")
        publisher_env = step(RELEASE, "publish", "Verify bundle and create or resume the draft")["env"]
        for name in ("jar", "checksum", "manifest", "notes"):
            self.assertEqual(publisher_env[name.upper() + "_SHA256"],
                             "${{ needs.build.outputs." + name + "_sha256 }}")
        self.assertEqual(publisher_env["RELEASE_SHA"], "${{ github.sha }}")
        self.assertEqual(publisher_env["RELEASE_TAG"], "${{ github.ref_name }}")

    def test_all_master_prs_have_a_check_context(self):
        self.assertIn("pull_request", CI["on"])
        self.assertEqual(CI["on"]["pull_request"], "")
        self.assertIn("build-and-test", CI["jobs"])
        self.assertNotIn("if", CI["jobs"]["build-and-test"])
        self.assertEqual(CI["on"]["push"]["paths-ignore"], ["trainer/**"])
        for item in CI["jobs"]["build-and-test"]["steps"][1:]:
            self.assertEqual(item["if"], "steps.scope.outputs.backend == 'true'")

    def test_embedded_python_and_shell_syntax(self):
        for workflow in (RELEASE, CI):
            for job in workflow["jobs"].values():
                for item in job["steps"]:
                    if "run" not in item:
                        continue
                    subprocess.run(["bash", "-n"], input=item["run"], text=True, check=True)
                    if "<<'PYTHON'" in item["run"]:
                        compile(python_code(item), item["name"], "exec")


class BundleTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.directory = Path(self.temp.name) / "release-assets"
        self.directory.mkdir()
        self.jar = Path(self.temp.name) / "engine-uci-0.6.0.jar"
        self.jar.write_bytes(b"verified executable fixture")
        (self.directory / "release-notes.md").write_text("## [0.6.0] release\n\nValidated notes.\n")
        self.output = Path(self.temp.name) / "outputs"
        self.env = {
            "RUNNER_TEMP": self.temp.name, "JAR": str(self.jar),
            "VERSION": "0.6.0", "TAG": "v0.6.0", "SOURCE": "a" * 40,
            "GITHUB_OUTPUT": str(self.output), "ASSET_DIR": str(self.directory),
            "RELEASE_TAG": "v0.6.0", "RELEASE_SHA": "a" * 40,
            "RELEASE_REF_TYPE": "tag", "GITHUB_REPOSITORY": "example/engine",
        }
        self.environment = patch.dict(os.environ, self.env)
        self.environment.start()
        self.addCleanup(self.environment.stop)
        exec(PREPARE, {"__name__": "test"})
        for line in self.output.read_text().splitlines():
            key, value = line.split("=", 1)
            os.environ[key.upper()] = value
        self.code = {"__name__": "test"}
        exec(PUBLISHER, self.code)

    def anchor(self, name, key):
        os.environ[key] = hashlib.sha256((self.directory / name).read_bytes()).hexdigest()

    def test_builder_and_publisher_agree(self):
        directory, manifest, expected, notes = self.code["verify_bundle"]()
        self.assertEqual(directory, self.directory)
        self.assertEqual(manifest["source_commit"], "a" * 40)
        self.assertEqual(manifest["artifact"], "engine-uci-0.6.0.jar")
        self.assertEqual(len(expected), 4)
        self.assertIn("Validated", notes)

    def test_missing_extra_substituted_and_symlinked_files_fail(self):
        for change in ("missing", "extra", "substitute", "symlink", "directory"):
            with self.subTest(change=change):
                jar = self.directory / self.jar.name
                original = jar.read_bytes()
                extra = self.directory / "unexpected"
                if change == "missing": jar.unlink()
                if change == "extra": extra.write_bytes(b"extra")
                if change == "substitute": jar.write_bytes(b"substituted")
                if change == "symlink":
                    jar.unlink()
                    jar.symlink_to(self.jar)
                if change == "directory": extra.mkdir()
                with self.assertRaises(RuntimeError): self.code["verify_bundle"]()
                if jar.exists() or jar.is_symlink(): jar.unlink()
                jar.write_bytes(original)
                if extra.is_dir(): extra.rmdir()
                elif extra.exists(): extra.unlink()

    def test_each_transferred_file_is_bound_to_build_digest(self):
        for key, name in (("JAR", self.jar.name), ("CHECKSUM", self.jar.name + ".sha256"),
                          ("MANIFEST", "release-manifest-0.6.0.json"), ("NOTES", "release-notes.md")):
            with self.subTest(file=name):
                path = self.directory / name
                old = path.read_bytes()
                path.write_bytes(old + b"substitution")
                with self.assertRaisesRegex(RuntimeError, "Build digest mismatch"):
                    self.code["verify_bundle"]()
                path.write_bytes(old)
                with patch.dict(os.environ, {key + "_SHA256": ""}):
                    with self.assertRaisesRegex(RuntimeError, "Missing build digest"):
                        self.code["verify_bundle"]()

    def test_wrong_manifest_fields_fail_even_with_matching_transfer_digest(self):
        path = self.directory / "release-manifest-0.6.0.json"
        original = path.read_text()
        for key, value in (("tag", "v0.6.1"), ("source_commit", "b" * 40), ("version", "0.6.1"),
                           ("artifact", "other.jar"), ("minimum_java_version", 17), ("sha256", "b" * 64)):
            with self.subTest(field=key):
                manifest = json.loads(original)
                manifest[key] = value
                path.write_text(json.dumps(manifest))
                self.anchor(path.name, "MANIFEST_SHA256")
                with self.assertRaisesRegex(RuntimeError, "Manifest does not match"):
                    self.code["verify_bundle"]()
        path.write_text(original)
        self.anchor(path.name, "MANIFEST_SHA256")

    def test_bad_checksum_and_notes_fail_even_with_matching_transfer_digest(self):
        for name, key, content in ((self.jar.name + ".sha256", "CHECKSUM_SHA256", "bad checksum"),
                                   ("release-notes.md", "NOTES_SHA256", "## [0.6.1]\n\nwrong notes")):
            with self.subTest(file=name):
                path = self.directory / name
                old = path.read_bytes()
                path.write_text(content)
                self.anchor(name, key)
                with self.assertRaises(RuntimeError): self.code["verify_bundle"]()
                path.write_bytes(old)
                self.anchor(name, key)

    def test_wrong_event_tag_or_source_fails(self):
        for key, value in (("RELEASE_REF_TYPE", "branch"), ("RELEASE_TAG", "v0.6.0-rc1"),
                           ("RELEASE_TAG", "v00.6.0"), ("RELEASE_SHA", "short")):
            with patch.dict(os.environ, {key: value}):
                with self.assertRaises(RuntimeError): self.code["verify_bundle"]()

    def github(self, digest_supported=True):
        fixture = FakeGitHub(self.directory, digest_supported)
        self.code["api"] = fixture.api
        return fixture

    def publish(self, fixture):
        with patch.object(subprocess, "run", fixture.upload):
            self.code["publish"](*self.code["verify_bundle"]())

    def test_new_draft_checks_api_content_digests(self):
        remote = self.github()
        self.publish(remote)
        self.assertTrue(remote.release["draft"])
        self.assertEqual(len(remote.assets), 3)
        self.assertEqual(remote.downloads, 0)

    def test_missing_api_digest_downloads_and_hashes_bytes(self):
        remote = self.github(False)
        self.publish(remote)
        self.assertEqual(remote.downloads, 3)

    def test_partial_upload_can_be_retried(self):
        remote = self.github()
        remote.fail_upload = 2
        with self.assertRaises(subprocess.CalledProcessError): self.publish(remote)
        self.assertTrue(remote.release["draft"])
        self.assertEqual(len(remote.assets), 1)
        remote.fail_upload = None
        self.publish(remote)
        self.assertEqual(len(remote.assets), 3)
        self.publish(remote)  # Complete-draft retry verifies the old source manifest.

    def test_published_release_api_failure_and_tag_mismatch_prevent_mutation(self):
        for case in ("published", "api-error", "wrong-tag", "unexpected", "wrong-manifest", "duplicate"):
            with self.subTest(case=case):
                remote = self.github()
                if case == "published": remote.release = {"id": 1, "tag_name": "v0.6.0", "draft": False}
                if case == "api-error": remote.error = True
                if case == "wrong-tag": remote.source = "b" * 40
                if case == "unexpected":
                    remote.release = {"id": 1, "tag_name": "v0.6.0", "draft": True}
                    remote.add("unexpected.jar", b"other")
                if case == "wrong-manifest":
                    remote.release = {"id": 1, "tag_name": "v0.6.0", "draft": True}
                    remote.add("release-manifest-0.6.0.json", b'{"source_commit":"wrong"}')
                if case == "duplicate":
                    remote.release = {"id": 1, "tag_name": "v0.6.0", "draft": True}
                    remote.duplicate = True
                with self.assertRaises((RuntimeError, subprocess.CalledProcessError)): self.publish(remote)
                self.assertEqual(remote.mutations, 0)

    def test_annotated_tag_is_peeled(self):
        remote = self.github()
        remote.annotated = True
        self.publish(remote)

    def test_remote_hash_mismatch_fails_with_and_without_api_digest(self):
        for supported in (True, False):
            with self.subTest(digest_supported=supported):
                remote = self.github(supported)
                remote.corrupt = True
                with self.assertRaisesRegex(RuntimeError, "digest mismatch"): self.publish(remote)

    def test_missing_extra_and_incomplete_remote_assets_fail(self):
        for fault in ("missing", "extra", "size", "state"):
            with self.subTest(fault=fault):
                remote = self.github()
                remote.asset_fault = fault
                with self.assertRaises(RuntimeError): self.publish(remote)

    def test_changed_draft_tag_and_mid_run_tag_movement_fail(self):
        remote = self.github()
        remote.move_tag_after_first_upload = True
        with self.assertRaisesRegex(RuntimeError, "Remote tag"): self.publish(remote)
        self.assertEqual(remote.uploads, 1)
        remote.release["tag_name"] = "v0.6.1"
        with self.assertRaisesRegex(RuntimeError, "Draft tag mismatch"):
            self.code["require_draft"]("repos/example/engine", 1, "v0.6.0")

    def test_publishing_during_a_retry_is_rejected(self):
        remote = self.github()
        remote.publish_after_first_upload = True
        with self.assertRaisesRegex(RuntimeError, "published release"): self.publish(remote)
        self.assertEqual(remote.uploads, 1)


class FakeGitHub:
    def __init__(self, directory, digest_supported):
        self.directory, self.digest_supported = directory, digest_supported
        self.release = None
        self.assets, self.bytes = {}, {}
        self.source = "a" * 40
        self.downloads = self.mutations = self.uploads = 0
        self.fail_upload = None
        self.error = self.annotated = self.corrupt = self.duplicate = False
        self.publish_after_first_upload = False
        self.move_tag_after_first_upload = False
        self.asset_fault = None

    def add(self, name, data):
        identity = max(self.bytes, default=0) + 1
        self.assets[name] = {"id": identity, "name": name, "state": "uploaded", "size": len(data),
                             "digest": "sha256:" + hashlib.sha256(data).hexdigest() if self.digest_supported else None}
        self.bytes[identity] = data

    def api(self, path, method="GET", body=None, binary=False):
        if self.error: raise subprocess.CalledProcessError(1, "gh api")
        if "/git/ref/tags/" in path:
            return {"object": {"type": "tag" if self.annotated else "commit", "sha": self.source}}
        if "/git/tags/" in path: return {"object": {"type": "commit", "sha": self.source}}
        if "/releases/assets/" in path:
            self.downloads += 1
            data = self.bytes[int(path.rsplit("/", 1)[1])]
            return b"corrupt" if self.corrupt else data
        if "/releases/1/assets?" in path:
            result = list(self.assets.values())
            if self.asset_fault == "missing": result = result[:-1]
            if self.asset_fault == "extra": result = result + [{"name": "extra"}]
            if self.asset_fault == "size": result = [dict(a, size=0) for a in result]
            if self.asset_fault == "state": result = [dict(a, state="starter") for a in result]
            if self.corrupt and self.digest_supported:
                result = [dict(a, digest="sha256:" + "b" * 64) for a in result]
            return result
        if path.endswith("/releases/1"):
            if method == "PATCH": self.mutations += 1
            return self.release
        if "/releases?" in path:
            return [self.release] * (2 if self.duplicate else 1) if self.release else []
        if path.endswith("/releases") and method == "POST":
            self.mutations += 1
            self.release = dict(body, id=1)
            return self.release
        raise AssertionError((path, method))

    def upload(self, command, check):
        self.uploads += 1
        if self.uploads == self.fail_upload: raise subprocess.CalledProcessError(1, command)
        assert command[:3] == ["gh", "release", "upload"] and check
        path = Path(command[4])
        self.mutations += 1
        self.add(path.name, path.read_bytes())
        if self.publish_after_first_upload: self.release["draft"] = False
        if self.move_tag_after_first_upload: self.source = "b" * 40


class ScopeTests(unittest.TestCase):
    def scope(self, base, files, count=None):
        with tempfile.TemporaryDirectory() as directory:
            output = Path(directory) / "outputs"
            env = {"EVENT_NAME": "pull_request", "BASE_REF": base, "PR_NUMBER": "1",
                   "GITHUB_REPOSITORY": "example/engine", "GITHUB_OUTPUT": str(output)}
            count = len(files) if count is None else count
            with patch.dict(os.environ, env), patch.object(subprocess, "check_output", side_effect=[
                json.dumps({"changed_files": count}).encode(), json.dumps([files]).encode()
            ]) as get:
                exec(SCOPE, {"__name__": "test"})
                if base == "master": get.assert_not_called()
            return output.read_text().strip()

    def test_master_trainer_only_always_runs_backend(self):
        self.assertEqual(self.scope("master", [{"filename": "trainer/train.py"}]), "backend=true")

    def test_feature_trainer_only_skips_expensive_backend_work(self):
        self.assertEqual(self.scope("develop", [{"filename": "trainer/train.py"}]), "backend=false")

    def test_mixed_changes_and_renames_run_backend(self):
        for file in ({"filename": "engine-core/pom.xml"},
                     {"filename": "trainer/moved.py", "previous_filename": "engine-core/moved.py"}):
            self.assertEqual(self.scope("develop", [file]), "backend=true")

    def test_incomplete_inventory_fails_closed_and_large_pr_runs_backend(self):
        with self.assertRaisesRegex(RuntimeError, "Incomplete"): self.scope("develop", [], count=2)
        self.assertEqual(self.scope("develop", [], count=3001), "backend=true")


if __name__ == "__main__":
    unittest.main()
