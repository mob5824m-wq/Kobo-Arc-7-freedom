#!/usr/bin/env python3
"""Pinned archive/default-All repair tests: production DEX edits + generated migration method.

Replays the exact SQL/bindings emitted by the generated Java method against real host
SQLite. Android lifecycle/signature/network/provider behaviour still needs device testing.
"""
from pathlib import Path
import base64
import importlib.util
import os
import shutil
import sqlite3
import subprocess
import tempfile
import zipfile
from androguard.core.dex import DEX
from cryptography import x509
from loguru import logger

ROOT = Path(__file__).resolve().parent.parent
logger.disable('androguard')


def load_patcher():
    spec = importlib.util.spec_from_file_location('catalog_patcher', ROOT/'scripts/patch-fdroid-apk.py')
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def method_block(text, start):
    # Count braces outside string literals; Java snippets here contain no char literals/comments with braces.
    begin = text.index('{', start)
    depth, quote, escape = 0, False, False
    for index in range(begin, len(text)):
        c = text[index]
        if quote:
            if escape:
                escape = False
            elif c == '\\':
                escape = True
            elif c == '"':
                quote = False
        elif c == '"':
            quote = True
        elif c == '{':
            depth += 1
        elif c == '}':
            depth -= 1
            if depth == 0:
                return text[start:index+1]
    raise AssertionError('Unterminated generated Java migration block')


def main():
    patcher = load_patcher()
    apk = ROOT/'releases/FDroidClassicLegacy.apk'
    assert apk.is_file(), 'Build the signed F-Droid APK first'
    with zipfile.ZipFile(apk) as archive:
        catalog = []
        original = archive.read('resources.arsc')
        patched, count = patcher.patch_arsc_bytes(original, catalog)
        second = []
        again, count_again = patcher.patch_arsc_bytes(patched, second)
        assert count == count_again == 7 and original == patched == again and catalog == second
        assert len(catalog) == 2 and len({row[0] for row in catalog}) == 2
        for address, label, certificate in catalog:
            assert address.startswith('http://127.0.0.1:21120/__https/') and address.endswith('/archive')
            x509.load_der_x509_certificate(bytes.fromhex(certificate))
        # The certs are exactly those shipped by the existing trusted bundle, not an HTTPS-derived trust replacement.
        old_apk = subprocess.check_output(['git', 'show', 'HEAD:app/src/main/assets/starter-apps/FDroidClassicLegacy.apk'], cwd=ROOT)
        import io
        with zipfile.ZipFile(io.BytesIO(old_apk)) as old:
            old_catalog = []
            patcher.patch_arsc_bytes(old.read('resources.arsc'), old_catalog)
        assert {(row[0], row[2]) for row in old_catalog} == {(row[0], row[2]) for row in catalog}
        # Check real instructions, including idempotence of the exact patch, not code-unit byte patterns.
        matched = 0
        for name in archive.namelist():
            if not name.endswith('.dex'):
                continue
            raw = archive.read(name)
            assert patcher.patch_available_default_all(raw) == raw, f'Non-idempotent All patch in {name}'
            for cls in DEX(raw).get_classes():
                if cls.get_name() != 'Lorg/fdroid/fdroid/views/fragments/AvailableAppsFragment;':
                    continue
                for method in cls.get_methods():
                    if method.get_name() not in ('onCreateView', 'onResume'):
                        continue
                    calls = '\n'.join(i.get_output() for i in method.get_instructions() if i.get_op_value() in (0x71, 0x77))
                    assert 'getCategoryAll' in calls and 'getCategoryWhatsNew' not in calls
                    matched += 1
        assert matched == 2
    print('PASS F-Droid: two parseable pinned archive certificates preserved, seven unique default repos, default/fallback All in actual DEX, idempotent resources/DEX edits')
    tools = Path(os.environ.get('TOOLCHAIN_DIR', ROOT/'build/toolchain'))
    java = os.environ.get('JAVA') or str(tools/'jdk/bin/java')
    javac = os.environ.get('JAVAC') or str(tools/'jdk/bin/javac')
    if not Path(java).is_file(): java = shutil.which('java')
    if not Path(javac).is_file(): javac = shutil.which('javac')
    assert java and javac
    with tempfile.TemporaryDirectory(prefix='arc7-fdroid-catalog-') as temp:
        temp = Path(temp)
        _, implementations = patcher.build_fdroid_patch_sources(str(temp/'generated'), str(ROOT))
        bridge = next(Path(p).read_text() for p in implementations if p.endswith('/Arc7Bridge.java'))
        start = bridge.index('if (!appCtx.getSharedPreferences("arc7_repo_migrations", 0).getBoolean("legacy1306",false))')
        migration = method_block(bridge, start)
        files = {
            'Context.java': '''import java.io.*;import java.util.*;class Context {
 final File root;final Map<String,SharedPreferences> prefs=new HashMap<>();Context(File f){root=f;}
 Assets getAssets(){return new Assets();} class Assets {InputStream open(String n)throws Exception{return new FileInputStream(new File(root,n));}}
 SharedPreferences getSharedPreferences(String n,int mode){return prefs.computeIfAbsent(n,k->new SharedPreferences());}}
 class SharedPreferences {final Map<String,Object> values=new HashMap<>();boolean getBoolean(String k,boolean d){Object v=values.get(k);return v instanceof Boolean?(Boolean)v:d;}
 Editor edit(){return new Editor();} class Editor {Editor remove(String k){values.remove(k);return this;}Editor putBoolean(String k,boolean v){values.put(k,v);return this;}boolean commit(){return true;}}}''',
            'SQLiteDatabase.java': '''import java.util.*;import java.nio.charset.*;class SQLiteDatabase {boolean fail;int calls;SQLiteDatabase(boolean f){fail=f;}
 static String enc(String s){return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8));}
 void beginTransaction(){System.out.println("BEGIN");}void setTransactionSuccessful(){System.out.println("SUCCESS");}void endTransaction(){System.out.println("END");}
 void execSQL(String sql,Object[] args)throws Exception {if(fail&&++calls==2)throw new Exception("fixture DB failure");StringBuilder row=new StringBuilder("SQL\\t"+enc(sql));for(Object a:args)row.append('\\t').append(enc(a.toString()));System.out.println(row);}}''',
            'MigrationHarness.java': '''import java.io.*;public class MigrationHarness {
 static void migrate(Context appCtx,SQLiteDatabase db)throws Exception {'''+migration+'''}
 public static void main(String[] args)throws Exception {Context c=new Context(new File(args[0]));SQLiteDatabase db=new SQLiteDatabase(args[1].equals("fail"));
 try {migrate(c,db);migrate(c,db);System.out.println("DONE");}catch(Exception error){System.out.println("FAILED");}
 System.out.println("MIGRATED="+c.getSharedPreferences("arc7_repo_migrations",0).getBoolean("legacy1306",false));}}''',
        }
        for name, content in files.items():
            (temp/name).write_text(content)
        asset = temp/'arc7-legacy-repos.tsv'
        asset.write_text(''.join('\t'.join(row)+'\n' for row in catalog))
        subprocess.run([javac, '-d', str(temp), *(str(temp/n) for n in files)], check=True)
        def exercise(mode):
            return subprocess.check_output([java, '-cp', str(temp), 'MigrationHarness', str(temp), mode], text=True)
        output = exercise('ok')
        assert output.count('BEGIN\n') == output.count('SUCCESS\n') == output.count('END\n') == 1
        assert 'MIGRATED=true' in output and 'FAILED' not in output
        statements = []
        for line in output.splitlines():
            if line.startswith('SQL\t'):
                parts = [base64.b64decode(p).decode() for p in line.split('\t')[1:]]
                statements.append((parts[0], parts[1:]))
        assert len(statements) == 4
        database = sqlite3.connect(':memory:')
        database.execute('CREATE TABLE fdroid_repo(address TEXT,name TEXT,description TEXT,inuse INTEGER,priority INTEGER,pubkey TEXT,version INTEGER,pushRequests INTEGER,lastetag TEXT,lastUpdated INTEGER,timestamp INTEGER,username TEXT,password TEXT,fingerprint TEXT)')
        database.execute('INSERT INTO fdroid_repo VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)', (catalog[0][0], 'My archive', 'retained', 0, 9, 'user-key-must-stay', 13, 0, 'old-etag', 123, 123, 'alice', 'private credential', 'user-pin'))
        database.execute("INSERT INTO fdroid_repo(address,name,pubkey,inuse) VALUES ('https://private.invalid/repo','School','private-key',0)")
        before = database.execute("SELECT * FROM fdroid_repo WHERE name='School'").fetchone()
        for _ in range(2):  # SQL itself also remains idempotent if the migration flag could not be saved.
            with database:
                for statement, parameters in statements:
                    database.execute(statement, parameters)
        rows = database.execute('SELECT address,inuse,pubkey,username,password,fingerprint,lastetag,timestamp FROM fdroid_repo ORDER BY address').fetchall()
        assert len(rows) == 3
        existing = next(row for row in rows if row[0] == catalog[0][0])
        assert existing[1:] == (1, 'user-key-must-stay', 'alice', 'private credential', 'user-pin', None, 0)
        added = next(row for row in rows if row[0] == catalog[1][0])
        assert added[1] == 1 and added[2] == catalog[1][2]
        assert database.execute("SELECT * FROM fdroid_repo WHERE name='School'").fetchone() == before
        failure = exercise('fail')
        assert 'FAILED' in failure and 'MIGRATED=false' in failure and 'SUCCESS' not in failure and 'END' in failure
        asset.write_text('bad\trow\tno pinned certificate\n')
        malformed = exercise('ok')
        assert 'FAILED' in malformed and 'MIGRATED=false' in malformed and 'SQL\t' not in malformed
    print('PASS generated Java archive migration + real SQLite: insert missing only, enable once, retain certificates/credentials/custom repos, reset stale metadata/category, transactional failure and malformed-catalog retry')


if __name__ == '__main__':
    main()
