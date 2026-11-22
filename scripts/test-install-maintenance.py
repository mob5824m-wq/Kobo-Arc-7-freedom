#!/usr/bin/env python3
"""Offline executable regressions: F-Droid collection types, safe installs, full install parity.

Runs the ACTUAL generated F-Droid bridge and APK installer on a host JVM. Android APIs
are stubbed for installer policy tests; this is not physical Kobo / ROM validation.
--apks additionally reconstructs all 38 bundled signed APKs from the built suite and
compares them byte-for-byte with release files, including every default-app recipe.
"""
from pathlib import Path
import argparse
import hashlib
import importlib.util
import os
import shutil
import subprocess
import tempfile
import zipfile
import xml.etree.ElementTree as ET

ROOT = Path(__file__).resolve().parent.parent
SRC = ROOT / "app/src/main/java/ca/arc7freedom/app"


def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, ROOT / "scripts" / filename)
    loaded = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(loaded)
    return loaded


def run(command, **kwargs):
    return subprocess.run([str(x) for x in command], check=True, **kwargs)


def java_tools():
    tools = Path(os.environ.get("TOOLCHAIN_DIR", ROOT / "build/toolchain"))
    java = os.environ.get("JAVA") or (tools / "jdk/bin/java" if (tools / "jdk/bin/java").exists() else shutil.which("java"))
    javac = os.environ.get("JAVAC") or (tools / "jdk/bin/javac" if (tools / "jdk/bin/javac").exists() else shutil.which("javac"))
    android = Path(os.environ.get("ANDROID_JAR", tools / "sdk/platforms/android-17/android.jar"))
    if not android.exists():
        sdk = Path(os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", "/opt/toolchain/sdk")))
        android = sdk / "platforms/android-17/android.jar"
    assert java and javac and android.exists(), "Run scripts/bootstrap-toolchain.sh / install API17 and a JDK"
    return str(java), str(javac), str(android)


def check_fdroid(tmp, java, javac, android):
    patcher = module("fdroid_patcher", "patch-fdroid-apk.py")
    stubs, impl = patcher.build_fdroid_patch_sources(str(tmp / "generated"), str(ROOT))
    bridge = next(Path(p) for p in impl if p.endswith("/Arc7Bridge.java"))
    source = bridge.read_text()
    assert 'normalizeStringArray(repoDetails.get("mirrors"))' in source
    assert 'return normalizeRequests(m.invoke(updater, mapper, parser))' in source
    assert '(String[]) repoDetails.get' not in source
    harness = tmp / "FdroidArrayHarness.java"
    harness.write_text(r'''package org.fdroid.fdroid;
import java.util.*; import java.io.*;
public class FdroidArrayHarness {
 static void check(boolean ok) { if (!ok) throw new AssertionError(); }
 public static void main(String[] args) throws Exception {
   String[] expected={"https://one/repo","https://two/repo"};
   check(Arrays.equals(expected,Arc7Bridge.normalizeStringArray(new ArrayList<String>(Arrays.asList(expected)))));
   check(Arrays.equals(expected,Arc7Bridge.normalizeStringArray(expected)));
   check(Arrays.equals(expected,Arc7Bridge.normalizeStringArray(new Object[]{expected[0],null,expected[1]})));
   check(Arc7Bridge.normalizeStringArray(null).length==0);
   check(Arc7Bridge.normalizeStringArray(Collections.emptyList()).length==0);
   Map<String,Object> requests=new LinkedHashMap<String,Object>();
   requests.put("install",new ArrayList<String>(Arrays.asList("org.example.one","org.example.two")));
   requests.put("uninstall",new String[]{"org.example.old"}); requests.put("empty",null);
   Map<String,String[]> typed=Arc7Bridge.normalizeRequests(requests);
   check(typed.get("install").length==2 && typed.get("uninstall").length==1 && typed.get("empty").length==0);
   for (Object bad : new Object[]{"scalar",Integer.valueOf(1),Arrays.asList("ok",Integer.valueOf(2))}) {
      try { Arc7Bridge.normalizeStringArray(bad); throw new AssertionError("malformed array accepted"); }
      catch(IOException expectedError) {}
   }
   try { Arc7Bridge.normalizeRequests(Arrays.asList("not a map")); throw new AssertionError(); } catch(IOException expectedError) {}
   requests.put("bad",Arrays.asList(Boolean.TRUE));
   try { Arc7Bridge.normalizeRequests(requests); throw new AssertionError(); } catch(IOException expectedError) {}
   check(Arc7Bridge.normalizeRequests(null).isEmpty());
   System.out.println("PASS: actual generated F-Droid bridge normalizes ArrayList, String[], Object[], null/empty mirrors and request values; rejects malformed types without ClassCastException");
 }
}''')
    classes = tmp / "fd-classes"
    classes.mkdir()
    # Compile the entire generated bridge with the same API17 signatures as the release build.
    run([javac, "-source", "8", "-target", "8", "-bootclasspath", android, "-d", classes, *stubs, *impl, harness], stdout=subprocess.DEVNULL)
    run([java, "-cp", str(classes) + os.pathsep + android, "org.fdroid.fdroid.FdroidArrayHarness"])


STUBS = {
    "android/content/Intent.java": '''package android.content; public class Intent {
 public static final int FLAG_ACTIVITY_NEW_TASK=0x10000000; public static final String ACTION_INSTALL_PACKAGE="install",EXTRA_RETURN_RESULT="return";
 public Intent(Context c,Class<?> k){} public Intent(String action){} public Intent addFlags(int f){return this;}
 public Intent setDataAndType(android.net.Uri u,String t){return this;} public Intent putExtra(String k,boolean b){return this;} }''',
    "android/net/Uri.java": "package android.net; public class Uri { public static Uri fromFile(java.io.File f){return new Uri();} }",
    "android/content/res/AssetManager.java": '''package android.content.res; public class AssetManager {
 public java.io.File root; public java.io.InputStream open(String n) throws java.io.IOException { return new java.io.FileInputStream(new java.io.File(root,n)); } }''',
    "android/content/pm/PackageInfo.java": "package android.content.pm; public class PackageInfo { public String packageName; public int versionCode; }",
    "android/content/pm/ApplicationInfo.java": "package android.content.pm; public class ApplicationInfo { public String sourceDir,dataDir; }",
    "android/content/pm/PackageManager.java": '''package android.content.pm; public class PackageManager {
 public static final int SIGNATURE_MATCH=0; public boolean exists=true,signature=true; public int version=254; public String installed;
 public static class NameNotFoundException extends Exception {}
 public PackageInfo getPackageInfo(String p,int flags) throws NameNotFoundException { if(!exists)throw new NameNotFoundException(); PackageInfo i=new PackageInfo();i.packageName=p;i.versionCode=version;return i; }
 public ApplicationInfo getApplicationInfo(String p,int flags) throws NameNotFoundException { if(!exists)throw new NameNotFoundException(); ApplicationInfo i=new ApplicationInfo();i.sourceDir=installed;return i; }
 public PackageInfo getPackageArchiveInfo(String p,int f){PackageInfo i=new PackageInfo(); i.packageName="org.example.patch";i.versionCode=254;return i;}
 public int checkSignatures(String a,String b){return signature?0:-3;}
}''',
    "android/content/Context.java": '''package android.content; public class Context {
 public android.content.res.AssetManager assets=new android.content.res.AssetManager(); public android.content.pm.PackageManager pm=new android.content.pm.PackageManager();
 public java.io.File cache; public boolean prompted;
 public Context getApplicationContext(){return this;} public android.content.res.AssetManager getAssets(){return assets;}
 public android.content.pm.PackageManager getPackageManager(){return pm;} public String getPackageName(){return "ca.arc7freedom.app";}
 public java.io.File getCacheDir(){return cache;} public android.content.pm.ApplicationInfo getApplicationInfo(){android.content.pm.ApplicationInfo i=new android.content.pm.ApplicationInfo();i.dataDir=cache.getParent();return i;}
 public void startActivity(Intent i){prompted=true;} }''',
    "ca/arc7freedom/app/TabletOptimizerManager.java": "package ca.arc7freedom.app; public class TabletOptimizerManager { public interface LogSink { void onLog(String s); } }",
    "ca/arc7freedom/app/InstallMaintenanceActivity.java": "package ca.arc7freedom.app; public class InstallMaintenanceActivity {}",
    "ca/arc7freedom/app/RootShell.java": '''package ca.arc7freedom.app; public class RootShell {
 public boolean rooted,writeInstalled; public int calls,code; public String output="Success",source,target;
 public static class ExecResult { public int exitCode;public String stdout; public ExecResult(int c,String o){exitCode=c;stdout=o;} }
 public boolean isRootAvailable(){return rooted;}
 public ExecResult execRoot(String script,Object listener){calls++; if(script.contains("uninstall")||script.contains("pm clear"))throw new AssertionError("destructive command");
 if(writeInstalled)try{java.nio.file.Files.copy(java.nio.file.Paths.get(source),java.nio.file.Paths.get(target),java.nio.file.StandardCopyOption.REPLACE_EXISTING);}catch(Exception e){throw new AssertionError(e);}
 return new ExecResult(code,output); } }''',
}

HARNESS = r'''package ca.arc7freedom.app;
import java.io.*;import java.nio.file.*;import java.util.*;import android.content.Context;
public class InstallHarness {
 static void check(boolean ok,String message){if(!ok)throw new AssertionError(message);}
 public static void main(String[] args)throws Exception {
  if(args.length>0 && args[0].equals("script")){System.out.print(BundledApk.installScript(args[1],args[2],"org.example.patch"));return;}
  File assets=new File(args[0]),out=new File(args[1]);out.mkdirs();
  BundledApk.Assets reader=new BundledApk.Assets(){public InputStream open(String p)throws IOException{return new FileInputStream(new File(assets,p));}};
  for(BundledApk.Entry entry:BundledApk.catalog(reader)) {
   File apk=BundledApk.extract(reader,entry,out);
   check(BundledApk.digest(new FileInputStream(apk)).equals(entry.sha256),"extraction mismatch");
  }
  if(args.length>2 && args[2].equals("all")){System.out.println("PASS: actual on-device Java extractor reconstructed every bundled signed APK");return;}
  check(BundledApk.decision(254,254,true,false).equals("INSTALL"),"same version skipped");
  check(BundledApk.decision(254,254,true,true).equals("CURRENT"),"exact APK not current");
  check(BundledApk.decision(255,254,false,false).equals("NEWER_KEPT"),"newer downgraded");
  check(BundledApk.decision(253,254,false,false).equals("BLOCKED"),"unreadable claimed repaired");
  check(BundledApk.decision(-1,254,false,false).equals("INSTALL"),"missing skipped");
  Context c=new Context();c.assets.root=assets;c.cache=new File(out,"cache");c.cache.mkdirs();
  File installed=new File(out,"installed.apk");Files.write(installed.toPath(),"old bytes".getBytes("UTF-8"));c.pm.installed=installed.toString();
  RootShell shell=new RootShell();shell.source=new File(assets,"fixture.apk").toString();shell.target=installed.toString();
  BundledAppInstaller installer=new BundledAppInstaller(c,shell);BundledApk.Entry entry=installer.find("org.example.patch");
  check(installer.inspect(entry).code.equals("INSTALL"),"same-version update hidden");
  check(installer.apply(entry,false,null).code.equals("PENDING") && shell.calls==0 && !c.prompted,"background prompt/root install without consent");
  check(!installer.installRequested(entry.pkg,null) && c.prompted,"prompt counted as installed");
  c.pm.signature=false;check(installer.apply(entry,true,null).code.equals("BLOCKED") && shell.calls==0,"signature mismatch tried destructive replacement");
  c.pm.signature=true;c.pm.version=255;check(installer.apply(entry,true,null).code.equals("NEWER_KEPT") && shell.calls==0,"newer app downgraded");c.pm.version=254;
  shell.code=1;shell.output="Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]";
  check(installer.apply(entry,true,null).code.equals("BLOCKED"),"failed update claimed installed");
  check(new String(Files.readAllBytes(installed.toPath()),"UTF-8").equals("old bytes"),"failed update erased data");
  shell.code=0;shell.output="Success";
  check(installer.apply(entry,true,null).code.equals("BLOCKED"),"pm output trusted without installed hash");
  shell.writeInstalled=true;
  check(installer.apply(entry,true,null).code.equals("UPDATED"),"verified update rejected");
  int calls=shell.calls;check(installer.apply(entry,true,null).code.equals("CURRENT") && calls==shell.calls,"idempotence failed");
  Files.delete(installed.toPath());check(installer.inspect(entry).code.equals("BLOCKED"),"unreadable installed APK hidden");
  Files.write(new File(assets,"fixture.apk").toPath(),"corrupted".getBytes("UTF-8"));
  try{BundledApk.extract(reader,entry,out);throw new AssertionError("corrupt payload accepted");}catch(IOException expected){}
  check(!new File(out,entry.filename+".part").exists(),"partial extraction survived");
  System.out.println("PASS: actual installer verifies same-version hashes, keeps newer/signature-conflicting apps/data, reports no-root/cancel/failure, rejects false pm Success and corrupt payloads, retries idempotently");
 }
}'''


def check_installer(tmp, java, javac):
    sources = tmp / "stubs"
    for name, text in STUBS.items():
        path = sources / name
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(text)
    harness = sources / "ca/arc7freedom/app/InstallHarness.java"
    harness.write_text(HARNESS)
    classes = tmp / "installer-classes"
    classes.mkdir()
    run([javac, "-source", "8", "-target", "8", "-d", classes, *sources.rglob("*.java"), SRC / "BundledApk.java", SRC / "BundledAppInstaller.java"], stdout=subprocess.DEVNULL)
    assets = tmp / "fixture-assets"
    (assets / "maintenance").mkdir(parents=True)
    data = b"signed APK fixture bytes"
    (assets / "fixture.apk").write_bytes(data)
    (assets / "maintenance/catalog.tsv").write_text("org.example.patch\tFixture\tFixture.apk\t254\t" + hashlib.sha256(data).hexdigest() + "\tasset:fixture.apk\n")
    run([java, "-cp", classes, "ca.arc7freedom.app.InstallHarness", assets, tmp / "extracted"])

    # Execute exactly the wrapper used by both automatic installs AND the upgrader, with fake pm.
    tools = tmp / "tools"
    tools.mkdir()
    pm = tools / "pm"
    pm.write_text('#!/bin/sh\necho "$*" >> "$PM_LOG"\necho "$PM_MESSAGE"\nexit "$PM_CODE"\n')
    pm.chmod(0o755)
    src = tmp / "file with spaces.apk"
    src.write_bytes(data)
    target = tmp / "target with spaces.apk"
    script = subprocess.check_output([java, "-cp", str(classes), "ca.arc7freedom.app.InstallHarness", "script", str(src), str(target)], text=True)
    # Only the fixed staging directory differs on host; pm target/quoting stay actual generated code.
    script = script.replace("mkdir -p /data/local/tmp", "true")
    for code, message, expected in ((0, "Success", 0), (1, "Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]", 32), (0, "Failure [INSTALL_FAILED_INSUFFICIENT_STORAGE]", 32), (1, "Success", 32)):
        log = tmp / "pm.log"
        result = subprocess.run(["sh", "-c", script], env=dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"], PM_LOG=str(log), PM_CODE=str(code), PM_MESSAGE=message), capture_output=True, text=True)
        assert result.returncode == expected, (result, script)
        assert "uninstall" not in log.read_text() and "clear" not in log.read_text()
        assert not target.exists(), "root staging file leaked"
        assert ("ARC7_APK_INSTALL_OK" in result.stdout) == (expected == 0)
    print("PASS: real shared root install script preserves failures/exit codes, quotes paths and cleans staging without any uninstall/clear")
    return classes


def check_entrypoints(tmp):
    ns = "{http://schemas.android.com/apk/res/android}"
    manifest = ET.parse(ROOT / "app/src/main/AndroidManifest.xml").getroot()
    application = manifest.find("application")
    for component in ("InstallMaintenanceService", "InstallMaintenanceActivity"):
        item = next(item for item in application if item.get(ns + "name") == "." + component)
        assert item.get(ns + "exported") == "false"
    for file in ("Arc7Application.java", "MainActivity.java", "BootNetworkReceiver.java", "Android4Installer.java"):
        assert "InstallMaintenanceService.request" in (SRC / file).read_text(), file
    for split in (ROOT / "split-apps").glob("*/AndroidManifest.xml"):
        assert "InstallMaintenanceService" not in split.read_text(), "standalone app must not patch other UIDs on launch"
    conversion = (SRC / "Android4Installer.java").read_text()
    assert 'addFileEntry(zos, "KoboArc7Freedom.apk", suiteApk)' in conversion
    assert "am startservice -n ca.arc7freedom.app/.InstallMaintenanceService" in conversion
    assert 'suiteZip.getEntry("assets/maintenance/catalog.tsv")' in conversion
    assert 'installer.apply(installer.find("ca.arc7freedom.launcher")' in conversion
    assert "VanillaAndroid4Home.apk.arc7-new" in conversion
    assert "queryIntentActivities(home, 0)" in conversion
    service = (SRC / "InstallMaintenanceService.java").read_text()
    assert "installer.catalog()" in service and "installer.apply(entry" in service
    assert "installBundledSystemCasOffline" in service and "provisionPython3AndAptSystemWide" in service
    assert "removeDefaultLauncherAndDiscontinuedApps(" not in service
    assert "setSystemWideTls13ProxyEnabled(true" not in service
    upgrader = (ROOT / "upgrader/src/main/java/ca/arc7freedom/upgrader/UpgraderActivity.java").read_text()
    assert "BundledApk.installScript" in upgrader and 'putExtra("show_install_patches"' not in upgrader
    assert "pm uninstall" not in upgrader and "pm clear" not in upgrader
    optimizer = (SRC / "TabletOptimizerManager.java").read_text()
    install_methods = optimizer.split("public boolean installBundledFDroidClassic", 1)[1].split("public boolean provisionPython3", 1)[0]
    assert install_methods.count(".installRequested(") == 3 and "pm uninstall" not in install_methods

    # Real shell helpers, executed against fake adb in a disposable repository layout.
    root = tmp / "checkout"
    (root / "tools").mkdir(parents=True)
    (root / "releases").mkdir()
    (root / "releases/KoboArc7Freedom.apk").write_bytes(b"fixture")
    tools = tmp / "adb-bin"
    tools.mkdir()
    adb = tools / "adb"
    adb.write_text('''#!/bin/sh
echo "$*" >> "$ADB_LOG"
case "$1" in
 install) if [ "$ADB_FAIL" = 1 ]; then echo 'Failure [INSTALL_FAILED_UPDATE_INCOMPATIBLE]'; exit 1; fi; echo Success ;;
 forward) exit 97 ;;
 shell) case "$*" in *getprop*) echo fixture ;; esac ;;
esac
''')
    adb.chmod(0o755)
    for name in ("usb-sideload-and-root.sh", "Backup-Kobo-To-Laptop-ADB.sh"):
        destination = root / "tools" / name
        shutil.copyfile(ROOT / "tools" / name, destination)
        for fail in ("0", "1"):
            log = tmp / (name + fail + ".log")
            result = subprocess.run(["bash", str(destination), "1"], env=dict(os.environ, PATH=str(tools) + os.pathsep + os.environ["PATH"], ADB_LOG=str(log), ADB_FAIL=fail), capture_output=True, text=True)
            calls = log.read_text()
            assert "install -r" in calls and "uninstall" not in calls and "rm -f /system/app" not in calls
            if fail == "1":
                assert result.returncode != 0 and "am start -n" not in calls, result
            else:
                assert "show_install_patches" not in calls and "am start -n ca.arc7freedom.app/.MainActivity" in calls
                assert result.returncode == (97 if name.startswith("Backup") else 0), result
    for name in ("usb-sideload-and-root.bat", "Backup-Kobo-To-Laptop-ADB.bat"):
        text = (ROOT / "tools" / name).read_text()
        assert "show_install_patches" not in text and "pm uninstall" not in text and "adb uninstall" not in text
        assert "rm -f /system/app" not in text
    helper = (ROOT / "tools/Arc7-Windows.ps1").read_text()
    assert "@('install','-r',$suite)" in helper and "In-place update was not confirmed" in helper
    assert "show_install_patches" not in helper and "pm uninstall" not in helper and "pm clear" not in helper
    for name in ("usb-sideload-and-root.bat", "Backup-Kobo-To-Laptop-ADB.bat"):
        text = (ROOT / "tools" / name).read_text()
        assert '-File "%ARC7_HELPER%"' in text and "DisableDelayedExpansion" in text
    print("PASS: APK/boot/update/root/conversion entrypoints share one pipeline; real USB/backup shell flows invoke it and stop safely on failed updates (Windows helper source gate here; separate native Windows CI)")


def check_apks(tmp, java, classes):
    main = ROOT / "releases/KoboArc7Freedom.apk"
    assets = tmp / "built-assets"
    assets.mkdir()
    with zipfile.ZipFile(main) as archive:
        for name in archive.namelist():
            if name.startswith(("assets/maintenance/", "assets/starter-apps/")) or name == "assets/VanillaAndroid4Home.apk":
                destination = assets / name.removeprefix("assets/")
                destination.parent.mkdir(parents=True, exist_ok=True)
                destination.write_bytes(archive.read(name))
    rows = [line.split("\t") for line in (assets / "maintenance/catalog.tsv").read_text().splitlines() if not line.startswith("#")]
    assert len(rows) == 38 and len({row[0] for row in rows}) == 38
    output = tmp / "built-extracted"
    run([java, "-cp", classes, "ca.arc7freedom.app.InstallHarness", assets, output, "all"])
    for pkg, label, filename, version, sha, source in rows:
        release = ROOT / "releases" / filename
        if not release.exists():
            release = ROOT / "releases/apps" / filename
        assert hashlib.sha256(release.read_bytes()).hexdigest() == sha, filename
        assert (output / filename).read_bytes() == release.read_bytes(), filename
    with zipfile.ZipFile(ROOT / "releases/Upgrade-My-Kobo-To-v2.7.0.apk") as archive:
        assert archive.read("assets/KoboArc7Freedom.apk") == main.read_bytes(), "upgrader's offline patch bundle is stale"
    print("PASS: every one of 38 embedded/recipe APKs equals its signed release byte-for-byte; upgrader embeds exactly the same complete suite")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--apks", action="store_true")
    args = parser.parse_args()
    java, javac, android = java_tools()
    with tempfile.TemporaryDirectory(prefix="arc7-install-tests-") as directory:
        tmp = Path(directory)
        check_fdroid(tmp, java, javac, android)
        classes = check_installer(tmp, java, javac)
        check_entrypoints(tmp)
        if args.apks:
            check_apks(tmp, java, classes)


if __name__ == "__main__":
    main()
