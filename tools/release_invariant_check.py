#!/usr/bin/env python3
"""v5.4.1 release architecture invariant gate.

Checks the RELEASE merged manifest, the RELEASE APK (including R8 DEX) and
production sources against the three frozen principles:

  NO_BACKGROUND_WORK        - component allowlist + background permission denylist
  NO_PERIODIC_POLLING       - scheduled/cyclic API denylist + watchdog one-shot shape
  NO_SELF_UPDATE_NETWORKING - network permission/dependency/source/DEX denylist
                              + updater absence

Usage:
  python tools/release_invariant_check.py MERGED_MANIFEST RELEASE_APK

Exit code 0 = all gates PASS, 1 = at least one gate FAIL.
This script only reports; it never edits code (human review on any hit).
"""
import os
import re
import sys
import xml.etree.ElementTree as ET
import zipfile

ANDROID = "{http://schemas.android.com/apk/res/android}"

ALLOWED_ACTIVITIES = {
    "io.github.yylsping.coolapkpurifier.ModuleConfigActivity",
}
ALLOWED_PROVIDERS = {
    "io.github.libxposed.service.XposedProvider",
}

BACKGROUND_PERMISSION_DENYLIST = [
    "android.permission.FOREGROUND_SERVICE",
    "android.permission.RECEIVE_BOOT_COMPLETED",
    "android.permission.SCHEDULE_EXACT_ALARM",
    "android.permission.USE_EXACT_ALARM",
    "android.permission.REQUEST_IGNORE_BATTERY_OPTIMIZATIONS",
    "android.permission.WAKE_LOCK",
]

NETWORK_PERMISSION_DENYLIST = [
    "android.permission.INTERNET",
    "android.permission.ACCESS_NETWORK_STATE",
]

POLLING_SOURCE_DENYLIST = [
    (r"scheduleAtFixedRate", "scheduleAtFixedRate"),
    (r"scheduleWithFixedDelay", "scheduleWithFixedDelay"),
    (r"Timer\.scheduleAtFixedRate", "Timer.scheduleAtFixedRate"),
    (r"PeriodicWorkRequest", "PeriodicWorkRequest"),
    (r"setRepeating", "setRepeating"),
    (r"setInexactRepeating", "setInexactRepeating"),
    (r"while\s*\(\s*true\s*\)", "while(true)"),
]

NETWORK_SOURCE_DENYLIST = [
    (r"java\.net\.URL\b", "java.net.URL"),
    (r"URLConnection", "URLConnection"),
    (r"HttpURLConnection", "HttpURLConnection"),
    (r"java\.net\.Socket\b", "java.net.Socket"),
    (r"javax\.net\.ssl", "javax.net.ssl"),
    (r"OkHttpClient", "OkHttpClient"),
    (r"\bRetrofit\b", "Retrofit"),
    (r"DownloadManager", "DownloadManager"),
    (r"\bCronet\b", "Cronet"),
    (r"SocketChannel", "SocketChannel"),
    (r"raw\.githubusercontent", "raw.githubusercontent"),
    (r"api\.github\.com", "GitHub API endpoint"),
]

UPDATER_ABSENCE_DENYLIST = [
    (r"UpdateActivity", "UpdateActivity"),
    (r"UpdateService", "UpdateService"),
    (r"UpdateWorker", "UpdateWorker"),
    (r"UpdateReceiver", "UpdateReceiver"),
    (r"checkForUpdates", "checkForUpdates"),
    (r"downloadApk", "downloadApk"),
    (r"installApk", "installApk"),
    (r"selfUpdate", "selfUpdate"),
]

NETWORK_DEX_DENYLIST = [
    b"Ljava/net/URL;",
    b"Ljava/net/URLConnection;",
    b"Ljava/net/HttpURLConnection;",
    b"Ljava/net/Socket;",
    b"Ljavax/net/ssl/",
    b"Lokhttp3/",
    b"Lretrofit2/",
    b"Landroid/app/DownloadManager;",
    b"Lorg/chromium/net/",
    b"Ljava/nio/channels/SocketChannel;",
    b"raw.githubusercontent",
    b"api.github.com",
]

PRODUCTION_DEPENDENCY_ALLOWLIST = {
    "io.github.libxposed:api",
    "io.github.libxposed:service",
    "org.jetbrains.kotlin:kotlin-stdlib",
    "org.luckypray:dexkit",
}

NETWORK_DEPENDENCY_DENYLIST = [
    "okhttp", "retrofit", "volley", "ktor-client", "cronet",
    "httpclient", "apache.http", "firebase", "analytics", "telemetry",
    "umeng", "bugly",
]

MODULE_PACKAGE_PREFIX = "io/github/yylsping/coolapkpurifier/"

failures = []


def report(ok, label, detail=""):
    print(f"[{'PASS' if ok else 'FAIL'}] {label}" + (f" :: {detail}" if detail else ""))
    if not ok:
        failures.append(label)


def check_manifest(manifest_path):
    tree = ET.parse(manifest_path)
    root = tree.getroot()

    permissions = {node.get(ANDROID + "name") for node in root.findall("uses-permission")}
    permissions |= {node.get(ANDROID + "name")
                    for node in root.findall("uses-permission-sdk-23")}
    permissions.discard(None)
    report(True, "manifest.permissions.present",
           ",".join(sorted(permissions)) or "none")
    for denied in BACKGROUND_PERMISSION_DENYLIST + NETWORK_PERMISSION_DENYLIST:
        report(denied not in permissions, f"manifest.permission.absent[{denied}]")

    app = root.find("application")
    components = {"activity": [], "provider": [], "service": [], "receiver": []}
    if app is not None:
        for kind in components:
            for node in app.findall(kind):
                components[kind].append(node.get(ANDROID + "name"))
    for name in components["activity"]:
        report(name in ALLOWED_ACTIVITIES, f"manifest.activity.allowlist[{name}]")
    for name in components["provider"]:
        report(name in ALLOWED_PROVIDERS, f"manifest.provider.allowlist[{name}]")
    report(not components["service"], "manifest.service.absent",
           ",".join(filter(None, components["service"])))
    report(not components["receiver"], "manifest.receiver.absent",
           ",".join(filter(None, components["receiver"])))


def iter_production_sources():
    base = os.path.join("app", "src", "main", "java")
    for root, _dirs, files in os.walk(base):
        for name in files:
            if name.endswith(".java"):
                yield os.path.join(root, name)


def strip_comments(text):
    text = re.sub(r"/\*.*?\*/", "", text, flags=re.DOTALL)
    text = re.sub(r"//[^\n]*", "", text)
    return text


def scan_source_denylist(denylist, label):
    hits = []
    for path in iter_production_sources():
        with open(path, encoding="utf-8") as fh:
            body = strip_comments(fh.read())
        for pattern, name in denylist:
            if re.search(pattern, body):
                hits.append(f"{os.path.basename(path)}:{name}")
    report(not hits, label, "; ".join(hits) if hits else "no hits")


def check_watchdog_shape():
    path = os.path.join("app", "src", "main", "java",
                        "io/github/yylsping/coolapkpurifier/HookCoordinator.java")
    with open(path, encoding="utf-8") as fh:
        body = strip_comments(fh.read())
    sites = re.findall(r"postDelayed\(\(\) -> watchdog\(", body)
    report(len(sites) == 2, "watchdog.oneShotPair", f"callSites={len(sites)}")
    match = re.search(r"private void watchdog\(.*?\n    \}", body, re.DOTALL)
    rearmed = bool(match and re.search(r"postDelayed|schedule", match.group(0)))
    report(not rearmed, "watchdog.noSelfReschedule")


def check_dex(apk_path):
    dex_blobs = []
    with zipfile.ZipFile(apk_path) as apk:
        dex_names = [n for n in apk.namelist()
                     if re.fullmatch(r"classes\d*\.dex", n)]
        for name in sorted(dex_names):
            dex_blobs.append((name, apk.read(name)))
        # Module production classes after R8 live under the module package.
        module_hits = []
        for name, blob in dex_blobs:
            for needle in NETWORK_DEX_DENYLIST:
                if needle in blob:
                    module_hits.append(f"{name}:{needle.decode()}")
    report(bool(dex_blobs), "apk.dex.present", f"count={len(dex_blobs)}")
    report(not module_hits, "apk.dex.networkDenylist",
           "; ".join(module_hits) if module_hits else "no hits")


def check_dependencies():
    path = os.path.join("app", "build.gradle.kts")
    with open(path, encoding="utf-8") as fh:
        body = strip_comments(fh.read())
    declared = set()
    for match in re.finditer(
            r"(?:implementation|api|compileOnly|runtimeOnly)\(\"([^\"]+)\"\)", body):
        parts = match.group(1).split(":")
        declared.add(":".join(parts[:2]))
    blocked = sorted(g for g in declared
                     if any(bad in g.lower() for bad in NETWORK_DEPENDENCY_DENYLIST))
    unknown = sorted(g for g in declared
                     if g not in PRODUCTION_DEPENDENCY_ALLOWLIST)
    report(not blocked, "dependencies.noNetworkSdk",
           "; ".join(blocked) if blocked else "no hits")
    report(not unknown, "dependencies.allowlist",
           "; ".join(unknown) if unknown else ",".join(sorted(declared)))


def main():
    if len(sys.argv) != 3:
        print(__doc__)
        return 2
    manifest, apk = sys.argv[1], sys.argv[2]
    print(f"gate manifest={manifest}")
    print(f"gate apk={apk}")

    check_manifest(manifest)
    scan_source_denylist(POLLING_SOURCE_DENYLIST, "source.pollingDenylist")
    scan_source_denylist(NETWORK_SOURCE_DENYLIST, "source.networkDenylist")
    scan_source_denylist(UPDATER_ABSENCE_DENYLIST, "source.updaterAbsence")
    check_watchdog_shape()
    check_dex(apk)
    check_dependencies()

    print(f"RESULT: {'PASS' if not failures else 'FAIL'} "
          f"({len(failures)} failing gates)")
    return 0 if not failures else 1


if __name__ == "__main__":
    sys.exit(main())
