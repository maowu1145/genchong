param([string]$JavaHome = $env:JAVA_HOME)
$ErrorActionPreference='Stop'
if(-not $JavaHome){throw 'Pass a Java17 JDK with -JavaHome'}
$project=Split-Path $PSScriptRoot -Parent
$work=Join-Path $project 'work/petstore-session-test'
New-Item -ItemType Directory -Force -Path $work | Out-Null
$jar=Join-Path $work 'json-20240303.jar'
if(-not (Test-Path -LiteralPath $jar)){Invoke-WebRequest -Uri 'https://repo.maven.apache.org/maven2/org/json/json/20240303/json-20240303.jar' -OutFile $jar -TimeoutSec 20}
@'
package android.content;
public abstract class Context {
 public static final int MODE_PRIVATE=0;
 public abstract Context getApplicationContext();
 public abstract SharedPreferences getSharedPreferences(String name,int mode);
}
'@ | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'Context.java')
@'
package android.content;
public interface SharedPreferences {
 String getString(String k,String fallback); float getFloat(String k,float fallback); boolean getBoolean(String k,boolean fallback); Editor edit();
 interface Editor { Editor putString(String k,String v); Editor remove(String k); void apply(); }
}
'@ | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'SharedPreferences.java')
@'
package android.util;
public class Log {public static int w(String t,String m,Throwable e){throw new AssertionError(m,e);}public static int e(String t,String m,Throwable e){throw new AssertionError(m,e);}}
'@ | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'Log.java')
@'
package com.maowu.gravitypet.gengpet;
public class PetCatalog {
 public static Object get(android.content.Context c,String id){return java.util.Set.of("doro","grudge_panda","spinning_cat","sweat_emoji","kinship_ride","thursday_bucket_cat").contains(id)?id:null;}
}
'@ | Set-Content -Encoding utf8 -LiteralPath (Join-Path $work 'PetCatalog.java')
$files=@('Context.java','SharedPreferences.java','Log.java','PetCatalog.java') | ForEach-Object {Join-Path $work $_}
$files += Join-Path $project 'app/src/main/java/com/maowu/gravitypet/gengpet/PetStore.java'
$files += Join-Path $PSScriptRoot 'PetStoreSessionTest.java'
$classes=Join-Path $work 'classes'
& (Join-Path $JavaHome 'bin/javac.exe') -encoding UTF-8 -cp $jar -d $classes $files
if($LASTEXITCODE -ne 0){throw 'Storage test compile failed'}
& (Join-Path $JavaHome 'bin/java.exe') -cp "$classes;$jar" PetStoreSessionTest
if($LASTEXITCODE -ne 0){throw 'Storage regression failed'}
