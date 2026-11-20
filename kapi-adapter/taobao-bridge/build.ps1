param([string]$Keystore = "$PSScriptRoot\..\module\module-dev.jks")

$ErrorActionPreference = 'Stop'
$projectRoot = $PSScriptRoot
$jdkRoot = $env:JAVA_HOME
$buildTools = "$env:ANDROID_HOME\build-tools\35.0.0"
$androidJar = "$env:ANDROID_HOME\platforms\android-35\android.jar"
$buildRoot = "$projectRoot\build\vector"
$outputApk = "$projectRoot\Kapi-Adapter-1.0.5.apk"
New-Item -ItemType Directory -Path "$buildRoot\classes", "$buildRoot\dex", "$buildRoot\generated" -Force | Out-Null
function CheckExit { if ($LASTEXITCODE -ne 0) { throw "Build command failed: $LASTEXITCODE" } }

$apiVersion = '102.0.0'
$apiArchive = "$buildRoot\libxposed-api-$apiVersion.aar"
if (-not (Test-Path -LiteralPath $apiArchive)) {
    Invoke-WebRequest "https://repo.maven.apache.org/maven2/io/github/libxposed/api/$apiVersion/api-$apiVersion.aar" -UseBasicParsing -OutFile $apiArchive
}
if ((Get-FileHash -LiteralPath $apiArchive).Hash -ne '423484A6E1807E7A423C4B88FCD8176D104318259D91791877FED88FE91479D0') {
    throw "Unexpected libxposed API archive: $apiArchive"
}
Add-Type -AssemblyName System.IO.Compression.FileSystem
$archive = [IO.Compression.ZipFile]::OpenRead($apiArchive)
try {
    $apiJar = "$buildRoot\libxposed-api-$apiVersion.jar"
    [IO.Compression.ZipFileExtensions]::ExtractToFile($archive.GetEntry('classes.jar'), $apiJar, $true)
} finally {
    $archive.Dispose()
}

& "$buildTools\aapt2.exe" compile --dir "$projectRoot\res" -o "$buildRoot\resources.zip"
CheckExit
& "$buildTools\aapt2.exe" link -o "$buildRoot\base.apk" --manifest "$projectRoot\AndroidManifest.xml" -I $androidJar --java "$buildRoot\generated" "$buildRoot\resources.zip"
CheckExit
$sources = (Get-ChildItem -LiteralPath "$projectRoot\src", "$projectRoot\..\native\src", "$projectRoot\..\module\src", "$buildRoot\generated" -Filter '*.java' -Recurse).FullName
& "$jdkRoot\bin\javac.exe" -encoding UTF-8 --release 17 -classpath "$androidJar;$apiJar" -d "$buildRoot\classes" $sources
CheckExit
& "$jdkRoot\bin\jar.exe" cf "$buildRoot\classes.jar" -C "$buildRoot\classes" .
CheckExit
& "$buildTools\d8.bat" --lib $androidJar --classpath $apiJar --min-api 28 --output "$buildRoot\dex" "$buildRoot\classes.jar"
CheckExit
Copy-Item -LiteralPath "$buildRoot\base.apk" -Destination "$buildRoot\unsigned.apk" -Force
& "$jdkRoot\bin\jar.exe" uf "$buildRoot\unsigned.apk" -C "$buildRoot\dex" classes.dex -C "$projectRoot\resources" META-INF/xposed
CheckExit
& "$buildTools\zipalign.exe" -f 4 "$buildRoot\unsigned.apk" "$buildRoot\aligned.apk"
CheckExit
if (-not (Test-Path -LiteralPath $Keystore)) {
    & "$jdkRoot\bin\keytool.exe" -genkeypair -keystore $Keystore -storepass android -keypass android -alias local -keyalg RSA -keysize 2048 -validity 3650 -dname 'CN=Kapi JD Local Adapter'
    CheckExit
}
& "$buildTools\apksigner.bat" sign --ks $Keystore --ks-pass pass:android --out $outputApk "$buildRoot\aligned.apk"
CheckExit
& "$buildTools\apksigner.bat" verify $outputApk
CheckExit
Write-Output "APK ready: $outputApk"
