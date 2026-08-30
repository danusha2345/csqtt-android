# SPDX-FileCopyrightText: 2026 amurcanov
# SPDX-License-Identifier: PolyForm-Noncommercial-1.0.0

[CmdletBinding()]
param(
    [ValidateNotNullOrEmpty()]
    [long[]]$Seeds = @(104729, 32452843, 49979687, 67867967, 86028121),

    [ValidateRange(1, 100000)]
    [int]$PassesPerSeed = 2,

    [ValidateRange(1, 1000000)]
    [int]$LifecycleSeedsPerRun = 256,

    [ValidateRange(1, 2147483647)]
    [int]$LifecycleSteps = 50000,

    [ValidateRange(2, 2147483647)]
    [int]$ParserCases = 1000000,

    [ValidateRange(60, 86400)]
    [int]$StepTimeoutSeconds = 1800,

    [switch]$HarnessSelfTestOnly
)

Set-StrictMode -Version Latest
$ErrorActionPreference = "Stop"
$script:FailureCode = 0
$trackedEnvironment = @(
    "CSQTT_SOAK_SEED",
    "CSQTT_ANDROID_LIFECYCLE_SEEDS",
    "CSQTT_ANDROID_LIFECYCLE_STEPS",
    "CSQTT_ANDROID_PARSER_CASES"
)
$savedEnvironment = @{}
$workspaceRoot = Split-Path -Parent $PSScriptRoot
$gradleExecutable = Join-Path $workspaceRoot "gradlew.bat"
$testResultRoot = Join-Path $workspaceRoot "app\build\test-results\testDebugUnitTest"
$lifecycleClass = "com.csqtt.client.TunnelLifecycleStateChaosTest"
$parserClass = "com.csqtt.client.TunnelEventParserTest"
$gradleCommonArguments = @(
    "--no-daemon",
    "--no-build-cache",
    "--rerun-tasks",
    "--console=plain",
    "--stacktrace"
)

function Set-ProcessEnvironment {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [AllowNull()]
        [string]$Value
    )

    [Environment]::SetEnvironmentVariable($Name, $Value, "Process")
}

function Stop-ExactProcessTree {
    param(
        [Parameter(Mandatory)]
        [Diagnostics.Process]$Process,

        [Parameter(Mandatory)]
        [string]$Name
    )

    $Process.Refresh()
    if ($Process.HasExited) {
        return
    }

    $taskKill = Join-Path $env:SystemRoot "System32\taskkill.exe"
    if (-not (Test-Path -LiteralPath $taskKill -PathType Leaf)) {
        throw "Cannot terminate $Name process tree because taskkill.exe is unavailable"
    }

    $killer = New-Object Diagnostics.Process
    try {
        $killer.StartInfo = New-NativeProcessStartInfo `
            -Executable $taskKill `
            -ArgumentList @("/PID", $Process.Id.ToString([Globalization.CultureInfo]::InvariantCulture), "/T", "/F")
        if (-not $killer.Start()) {
            throw "Failed to start taskkill for $Name"
        }
        if (-not $killer.WaitForExit(10000)) {
            $killer.Kill()
            throw "taskkill timed out while terminating $Name process tree rooted at PID $($Process.Id)"
        }
        $killer.WaitForExit()
        $killer.Refresh()
        $killExitCodeValue = $killer.ExitCode
        if ($null -eq $killExitCodeValue -or $killExitCodeValue -isnot [int]) {
            throw "taskkill returned an invalid exit code while terminating $Name"
        }
        [int]$killExitCode = $killExitCodeValue
    } finally {
        $killer.Dispose()
    }
    $Process.Refresh()
    if ($killExitCode -ne 0 -or -not $Process.HasExited) {
        throw "Failed to terminate exact $Name process tree rooted at PID $($Process.Id); taskkill exit code $killExitCode"
    }
}

function ConvertTo-NativeArgument {
    param(
        [Parameter(Mandatory)]
        [AllowEmptyString()]
        [string]$Value
    )

    if ($Value.Length -gt 0 -and $Value -notmatch '[\s"]') {
        return $Value
    }

    $builder = New-Object Text.StringBuilder
    [void]$builder.Append('"')
    [int]$backslashes = 0
    foreach ($character in $Value.ToCharArray()) {
        if ($character -eq '\') {
            $backslashes++
        } elseif ($character -eq '"') {
            [void]$builder.Append(('\' * (($backslashes * 2) + 1)))
            [void]$builder.Append('"')
            $backslashes = 0
        } else {
            if ($backslashes -gt 0) {
                [void]$builder.Append(('\' * $backslashes))
                $backslashes = 0
            }
            [void]$builder.Append($character)
        }
    }
    if ($backslashes -gt 0) {
        [void]$builder.Append(('\' * ($backslashes * 2)))
    }
    [void]$builder.Append('"')
    return $builder.ToString()
}

function New-NativeProcessStartInfo {
    param(
        [Parameter(Mandatory)]
        [string]$Executable,

        [Parameter(Mandatory)]
        [string[]]$ArgumentList
    )

    $resolvedExecutable = $Executable
    if ([IO.Path]::IsPathRooted($Executable)) {
        if (-not (Test-Path -LiteralPath $Executable -PathType Leaf)) {
            throw "Executable not found at $Executable"
        }
        $resolvedExecutable = (Resolve-Path -LiteralPath $Executable).Path
    }

    $startInfo = New-Object Diagnostics.ProcessStartInfo
    $startInfo.UseShellExecute = $false
    $startInfo.CreateNoWindow = $false
    $startInfo.WorkingDirectory = $workspaceRoot
    $extension = [IO.Path]::GetExtension($resolvedExecutable)
    if ($extension -ieq ".bat" -or $extension -ieq ".cmd") {
        $command = ConvertTo-NativeArgument -Value $resolvedExecutable
        if ($ArgumentList.Count -gt 0) {
            $command += " " + (($ArgumentList | ForEach-Object { ConvertTo-NativeArgument -Value $_ }) -join " ")
        }
        $startInfo.FileName = Join-Path $env:SystemRoot "System32\cmd.exe"
        $startInfo.Arguments = "/D /S /C `"$command`""
    } else {
        $startInfo.FileName = $resolvedExecutable
        $startInfo.Arguments = (($ArgumentList | ForEach-Object { ConvertTo-NativeArgument -Value $_ }) -join " ")
    }
    return $startInfo
}

function Invoke-NativeStep {
    param(
        [Parameter(Mandatory)]
        [string]$Name,

        [Parameter(Mandatory)]
        [string]$Executable,

        [Parameter(Mandatory)]
        [string[]]$ArgumentList,

        [ValidateRange(1, 86400)]
        [int]$TimeoutSeconds = $StepTimeoutSeconds
    )

    [Console]::Out.WriteLine("")
    [Console]::Out.WriteLine("=== $Name ===")
    $process = $null
    $started = $false
    $finished = $false
    try {
        $process = New-Object Diagnostics.Process
        $process.StartInfo = New-NativeProcessStartInfo `
            -Executable $Executable `
            -ArgumentList $ArgumentList
        if (-not $process.Start()) {
            throw "$Name failed to start"
        }
        $started = $true
        if (-not $process.WaitForExit($TimeoutSeconds * 1000)) {
            $script:FailureCode = 124
            Stop-ExactProcessTree -Process $process -Name $Name
            throw "$Name timed out after $TimeoutSeconds seconds; process tree rooted at PID $($process.Id) was terminated"
        }
        $process.WaitForExit()
        $process.Refresh()
        if (-not $process.HasExited) {
            throw "$Name process state is indeterminate after WaitForExit"
        }
        $finished = $true
        $exitCodeValue = $process.ExitCode
        if ($null -eq $exitCodeValue -or $exitCodeValue -isnot [int]) {
            throw "$Name returned an invalid exit code"
        }
        [int]$nativeCode = $exitCodeValue
        if ($nativeCode -ne 0) {
            if ($nativeCode -gt 0 -and $nativeCode -le 255) {
                $script:FailureCode = $nativeCode
            } else {
                $script:FailureCode = 1
            }
            throw "$Name failed with exit code $nativeCode"
        }
    } finally {
        if (-not $finished -and $null -ne $process -and $started) {
            $process.Refresh()
            if (-not $process.HasExited) {
                Stop-ExactProcessTree -Process $process -Name $Name
            }
        }
        if ($null -ne $process) {
            $process.Dispose()
        }
    }
}

function Assert-NativeStepFailClosed {
    $savedFailureCode = $script:FailureCode
    Invoke-NativeStep `
        -Name "Harness child exit 0 probe" `
        -Executable $env:ComSpec `
        -ArgumentList @("/D", "/S", "/C", "exit 0") `
        -TimeoutSeconds 30

    $caughtFailure = $false
    try {
        Invoke-NativeStep `
            -Name "Harness child exit 7 probe" `
            -Executable $env:ComSpec `
            -ArgumentList @("/D", "/S", "/C", "exit 7") `
            -TimeoutSeconds 30
    } catch {
        $caughtFailure = $true
        if ($script:FailureCode -ne 7) {
            throw "Harness exit 7 probe was caught with code $($script:FailureCode), expected 7"
        }
        if ($_.Exception.Message -notmatch 'exit code 7$') {
            throw "Harness exit 7 probe produced an unexpected failure: $($_.Exception.Message)"
        }
    } finally {
        $script:FailureCode = $savedFailureCode
    }
    if (-not $caughtFailure) {
        throw "Harness accepted a child process that exited with code 7"
    }
    [Console]::Out.WriteLine("Harness child exit probes passed")
}

function Get-ResultStamp {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    if (-not (Test-Path -LiteralPath $Path -PathType Leaf)) {
        return $null
    }
    $item = Get-Item -LiteralPath $Path
    return "$($item.LastWriteTimeUtc.Ticks):$($item.Length)"
}

function Get-ResultSnapshot {
    $snapshot = @{}
    if (-not (Test-Path -LiteralPath $testResultRoot -PathType Container)) {
        return $snapshot
    }
    foreach ($item in Get-ChildItem -LiteralPath $testResultRoot -Filter "TEST-*.xml" -File) {
        $snapshot[$item.FullName] = "$($item.LastWriteTimeUtc.Ticks):$($item.Length)"
    }
    return $snapshot
}

function Read-TestSuite {
    param(
        [Parameter(Mandatory)]
        [string]$Path
    )

    try {
        [xml]$document = Get-Content -LiteralPath $Path -Raw
    } catch {
        throw "Invalid JUnit XML at ${Path}: $($_.Exception.Message)"
    }
    if ($null -eq $document.testsuite) {
        throw "JUnit XML does not contain a testsuite at $Path"
    }
    return $document.testsuite
}

function Assert-TestSuite {
    param(
        [Parameter(Mandatory)]
        [string]$Path,

        [Parameter(Mandatory)]
        [long]$MinimumTests,

        [AllowNull()]
        [AllowEmptyString()]
        [string]$ExpectedClass
    )

    $suite = Read-TestSuite -Path $Path
    $tests = [long]$suite.tests
    $skipped = [long]$suite.skipped
    $failures = [long]$suite.failures
    $errors = [long]$suite.errors
    if ($tests -lt $MinimumTests) {
        throw "Expected at least $MinimumTests tests in $Path, but Gradle reported $tests"
    }
    if ($skipped -ne 0) {
        throw "Gradle skipped $skipped tests in $Path"
    }
    if ($failures -ne 0 -or $errors -ne 0) {
        throw "JUnit result contains $failures failures and $errors errors in $Path"
    }
    if (-not [string]::IsNullOrEmpty($ExpectedClass)) {
        if ([string]$suite.name -ne $ExpectedClass) {
            throw "Expected JUnit suite $ExpectedClass, found $($suite.name)"
        }
        foreach ($testCase in @($suite.testcase)) {
            if ([string]$testCase.classname -ne $ExpectedClass) {
                throw "Unexpected test class $($testCase.classname) in $Path"
            }
        }
    }
    return $tests
}

function Assert-ExactTestResult {
    param(
        [Parameter(Mandatory)]
        [string]$ClassName,

        [Parameter(Mandatory)]
        [long]$MinimumTests,

        [AllowNull()]
        [string]$PreviousStamp
    )

    $path = Join-Path $testResultRoot "TEST-$ClassName.xml"
    $currentStamp = Get-ResultStamp -Path $path
    if ($null -eq $currentStamp) {
        throw "Gradle produced no JUnit result for required test class $ClassName"
    }
    if ($null -ne $PreviousStamp -and $currentStamp -eq $PreviousStamp) {
        throw "JUnit result for required test class $ClassName was not regenerated"
    }
    return Assert-TestSuite -Path $path -MinimumTests $MinimumTests -ExpectedClass $ClassName
}

function Assert-FullTestResults {
    param(
        [Parameter(Mandatory)]
        [hashtable]$PreviousSnapshot
    )

    if (-not (Test-Path -LiteralPath $testResultRoot -PathType Container)) {
        throw "Gradle produced no test result directory at $testResultRoot"
    }

    $files = @(Get-ChildItem -LiteralPath $testResultRoot -Filter "TEST-*.xml" -File)
    if ($files.Count -eq 0) {
        throw "Gradle produced zero JUnit test suites"
    }

    [long]$totalTests = 0
    foreach ($file in $files) {
        $currentStamp = Get-ResultStamp -Path $file.FullName
        if ($PreviousSnapshot.ContainsKey($file.FullName) -and $PreviousSnapshot[$file.FullName] -eq $currentStamp) {
            throw "JUnit result was not regenerated for $($file.Name)"
        }
        $totalTests += Assert-TestSuite -Path $file.FullName -MinimumTests 1 -ExpectedClass $null
    }
    if ($totalTests -le 0) {
        throw "Gradle reported zero executed tests"
    }

    Assert-ExactTestResult `
        -ClassName $lifecycleClass `
        -MinimumTests 4 `
        -PreviousStamp $PreviousSnapshot[(Join-Path $testResultRoot "TEST-$lifecycleClass.xml")] | Out-Null
    Assert-ExactTestResult `
        -ClassName $parserClass `
        -MinimumTests 6 `
        -PreviousStamp $PreviousSnapshot[(Join-Path $testResultRoot "TEST-$parserClass.xml")] | Out-Null
    return $totalTests
}

function Get-EffectiveSeed {
    param(
        [Parameter(Mandatory)]
        [long]$BaseSeed,

        [Parameter(Mandatory)]
        [int]$Pass
    )

    [decimal]$wideSeed = [decimal]$BaseSeed + ([decimal]$Pass * [decimal]$LifecycleSeedsPerRun)
    [decimal]$lastLifecycleSeed = $wideSeed + [decimal]$LifecycleSeedsPerRun - 1
    if ($wideSeed -lt [decimal][long]::MinValue -or $lastLifecycleSeed -gt [decimal][long]::MaxValue) {
        throw "Seed range overflows signed 64-bit arithmetic for base seed $BaseSeed pass $Pass"
    }
    return [long]$wideSeed
}

function Set-ChaosEnvironment {
    param(
        [Parameter(Mandatory)]
        [long]$Seed
    )

    $culture = [Globalization.CultureInfo]::InvariantCulture
    Set-ProcessEnvironment "CSQTT_SOAK_SEED" $Seed.ToString($culture)
    Set-ProcessEnvironment "CSQTT_ANDROID_LIFECYCLE_SEEDS" $LifecycleSeedsPerRun.ToString($culture)
    Set-ProcessEnvironment "CSQTT_ANDROID_LIFECYCLE_STEPS" $LifecycleSteps.ToString($culture)
    Set-ProcessEnvironment "CSQTT_ANDROID_PARSER_CASES" $ParserCases.ToString($culture)
}

if ($Seeds.Count -eq 0) {
    throw "At least one seed is required"
}
if (-not $HarnessSelfTestOnly -and -not (Test-Path -LiteralPath $gradleExecutable -PathType Leaf)) {
    throw "Gradle wrapper not found at $gradleExecutable"
}

foreach ($name in $trackedEnvironment) {
    $savedEnvironment[$name] = [Environment]::GetEnvironmentVariable($name, "Process")
}

try {
    Assert-NativeStepFailClosed
    if ($HarnessSelfTestOnly) {
        [Console]::Out.WriteLine("Android stability harness self-test passed")
    } else {
    Set-ChaosEnvironment -Seed (Get-EffectiveSeed -BaseSeed $Seeds[0] -Pass 0)
    $fullSnapshot = Get-ResultSnapshot
    Invoke-NativeStep `
        -Name "Full Android debug unit test suite" `
        -Executable $gradleExecutable `
        -ArgumentList (@(":app:testDebugUnitTest") + $gradleCommonArguments)
    $fullTests = Assert-FullTestResults -PreviousSnapshot $fullSnapshot

    Invoke-NativeStep `
        -Name "Android debug lint" `
        -Executable $gradleExecutable `
        -ArgumentList (@(":app:lintDebug") + $gradleCommonArguments)

    [long]$executedChaosTests = 0
    [long]$executedChaosRuns = 0
    foreach ($baseSeed in $Seeds) {
        for ($pass = 0; $pass -lt $PassesPerSeed; $pass++) {
            $effectiveSeed = Get-EffectiveSeed -BaseSeed $baseSeed -Pass $pass
            Set-ChaosEnvironment -Seed $effectiveSeed
            $lifecyclePath = Join-Path $testResultRoot "TEST-$lifecycleClass.xml"
            $parserPath = Join-Path $testResultRoot "TEST-$parserClass.xml"
            $lifecycleStamp = Get-ResultStamp -Path $lifecyclePath
            $parserStamp = Get-ResultStamp -Path $parserPath
            Invoke-NativeStep `
                -Name "Android lifecycle and parser chaos seed $effectiveSeed pass $($pass + 1)" `
                -Executable $gradleExecutable `
                -ArgumentList (
                    @(
                        ":app:testDebugUnitTest",
                        "--tests",
                        $lifecycleClass,
                        "--tests",
                        $parserClass
                    ) + $gradleCommonArguments
                )
            $executedChaosTests += Assert-ExactTestResult `
                -ClassName $lifecycleClass `
                -MinimumTests 4 `
                -PreviousStamp $lifecycleStamp
            $executedChaosTests += Assert-ExactTestResult `
                -ClassName $parserClass `
                -MinimumTests 6 `
                -PreviousStamp $parserStamp
            $executedChaosRuns++
        }
    }

    $expectedChaosRuns = [long]$Seeds.Count * [long]$PassesPerSeed
    if ($executedChaosRuns -ne $expectedChaosRuns) {
        throw "Expected $expectedChaosRuns chaos runs, executed $executedChaosRuns"
    }
    if ($executedChaosTests -lt $expectedChaosRuns * 10) {
        throw "Expected at least $($expectedChaosRuns * 10) chaos tests, executed $executedChaosTests"
    }

    [Console]::Out.WriteLine("")
    [Console]::Out.WriteLine("Android stability soak passed")
    [Console]::Out.WriteLine("Full debug tests: $fullTests")
    [Console]::Out.WriteLine("Chaos runs: $executedChaosRuns")
    [Console]::Out.WriteLine("Chaos tests: $executedChaosTests")
    [Console]::Out.WriteLine("Base seeds: $($Seeds -join ', ')")
    [Console]::Out.WriteLine("Lifecycle seeds per run: $LifecycleSeedsPerRun")
    [Console]::Out.WriteLine("Lifecycle steps per seed: $LifecycleSteps")
    [Console]::Out.WriteLine("Parser cases per run: $ParserCases")
    }
} catch {
    if ($script:FailureCode -eq 0) {
        $script:FailureCode = 1
    }
    [Console]::Error.WriteLine($_.Exception.Message)
} finally {
    foreach ($name in $trackedEnvironment) {
        Set-ProcessEnvironment $name $savedEnvironment[$name]
    }
}

if ($script:FailureCode -ne 0) {
    exit $script:FailureCode
}
