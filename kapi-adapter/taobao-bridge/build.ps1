$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jdkRoot = $env:JAVA_HOME
$buildTools = "$env:ANDROID_HOME\build-tools\35.0.0"
$androidJar = "$env:ANDROID_HOME\platforms\android-35\android.jar"
$buildRoot = "$projectRoot\build"
New-Item -ItemType Directory -Path "$buildRoot\classes", "$buildRoot\dex" -Force | Out-Null
function CheckExit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }
python "$projectRoot\prepare_assets.py"
CheckExit
& "$buildTools\aapt2.exe" compile --dir "$projectRoot\res" -o "$buildRoot\resources.zip"
CheckExit
& "$buildTools\aapt2.exe" link -o "$buildRoot\base.apk" --manifest "$projectRoot\AndroidManifest.xml" -I $androidJar "$buildRoot\resources.zip" -A "$projectRoot\assets"
CheckExit
$sources = (Get-ChildItem -LiteralPath "$projectRoot\src" -Filter '*.java' -Recurse).FullName
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 --release 8 -classpath "$androidJar;$projectRoot\..\module\libs\xposed-api-82.jar" -d "$buildRoot\classes" $sources
CheckExit
& "$jdkRoot\bin\jar.exe" cf "$buildRoot\classes.jar" -C "$buildRoot\classes" .
CheckExit
& "$buildTools\d8.bat" --lib $androidJar --min-api 28 --output "$buildRoot\dex" "$buildRoot\classes.jar"
CheckExit
Copy-Item -LiteralPath "$buildRoot\base.apk" -Destination "$buildRoot\unsigned.apk" -Force
& "$jdkRoot\bin\jar.exe" uf "$buildRoot\unsigned.apk" -C "$buildRoot\dex" classes.dex
CheckExit
& "$buildTools\zipalign.exe" -f 4 "$buildRoot\unsigned.apk" "$buildRoot\aligned.apk"
CheckExit
if (-not (Test-Path -LiteralPath "$projectRoot\..\module\module-dev.jks")) {
    & "$jdkRoot\bin\keytool.exe" -genkeypair -keystore "$projectRoot\..\module\module-dev.jks" -storepass android -keypass android -alias local -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Kapi JD Local Adapter'
    CheckExit
}
& "$buildTools\apksigner.bat" sign --ks "$projectRoot\..\module\module-dev.jks" --ks-pass pass:android --out "$projectRoot\咔皮京东淘宝适配.apk" "$buildRoot\aligned.apk"
CheckExit
& "$buildTools\apksigner.bat" verify "$projectRoot\咔皮京东淘宝适配.apk"
CheckExit
Write-Output "APK ready: $projectRoot\咔皮京东淘宝适配.apk"
