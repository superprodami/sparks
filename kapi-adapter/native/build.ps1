$ErrorActionPreference = 'Stop'
$taskRoot = Split-Path $PSScriptRoot -Parent
$jdkRoot = $env:JAVA_HOME
$buildTools = "$env:ANDROID_HOME\build-tools\35.0.0"
$androidJar = "$env:ANDROID_HOME\platforms\android-35\android.jar"
$buildRoot = "$PSScriptRoot\build"
New-Item -ItemType Directory -Path "$buildRoot\classes", "$buildRoot\dex" -Force | Out-Null
function CheckExit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
python "$PSScriptRoot\package_standard.py" --extract
CheckExit
$sources = (Get-ChildItem -LiteralPath "$PSScriptRoot\src" -Filter '*.java' -Recurse).FullName
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 --release 8 -classpath $androidJar -d "$buildRoot\classes" $sources "$taskRoot\module\src\cn\local\kapijd\JdOrder.java"
CheckExit
& "$jdkRoot\bin\jar.exe" cf "$buildRoot\helper.jar" -C "$buildRoot\classes" cn
CheckExit
& "$buildTools\d8.bat" --lib $androidJar --min-api 28 --output "$buildRoot\dex" "$buildRoot\helper.jar"
CheckExit
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 -cp "$taskRoot\dex-tools\*" "$taskRoot\dex-tools\PatchDex.java" "$taskRoot\dex-tools\PatchFactory.java"
CheckExit
$dexTools = "$taskRoot\dex-tools;$taskRoot\dex-tools\*"
$unpacked = "$buildRoot\unpacked-1.30.01"
& "$jdkRoot\bin\java.exe" -Xmx2g -cp $dexTools PatchFactory $unpacked
CheckExit
& "$jdkRoot\bin\java.exe" -Xmx2g -cp $dexTools PatchDex "$unpacked\53035008.dex" "$buildRoot\dex\classes.dex" "$buildRoot\payment-patched.dex"
CheckExit
python "$PSScriptRoot\package_standard.py"
CheckExit
& "$buildTools\zipalign.exe" -P 16 -f 4 "$buildRoot\kapi-jd-standard-unaligned.apk" "$buildRoot\kapi-jd-taobao.apk"
CheckExit
