<#
.SYNOPSIS
    Applies tuned_params.txt values back to engine-core source files and syncs
    EvalParams.extractFromCurrentEval() in engine-tuner.

.DESCRIPTION
    After each per-group Texel tuning run the tuner writes tuned_params.txt.
    This script reads that file and patches only the group that was tuned,
    leaving all other eval constants untouched.

    Groups: material | pst | pawn-structure | king-safety | mobility | scalars | all

.PARAMETER Group
    Which parameter group to apply.  Use "all" to apply every section.

.PARAMETER TunedParamsFile
    Path to tuned_params.txt.  Defaults to <repo-root>/chess-engine/tuned_params.txt.

.PARAMETER EngineCoreSrc
    Path to engine-core/src/main/java.  Auto-detected from script location.

.PARAMETER EngineTunerSrc
    Path to engine-tuner/src/main/java.  Auto-detected from script location.

.EXAMPLE
    .\tools\apply-tuned-params.ps1 -Group scalars
.EXAMPLE
    .\tools\apply-tuned-params.ps1 -Group material
.EXAMPLE
    .\tools\apply-tuned-params.ps1 -Group pawn-structure
#>
param(
    [Parameter(Mandatory)]
    [ValidateSet('material','pst','pawn-structure','king-safety','mobility','scalars','all')]
    [string]$Group,

    [string]$TunedParamsFile = "",
    [string]$EngineCoreSrc   = "",
    [string]$EngineTunerSrc  = ""
)
Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

# ─── Path resolution ────────────────────────────────────────────────────────
$repoRoot = Split-Path $PSScriptRoot -Parent    # tools/ is one level below chess-engine/
$engineRoot = $PSScriptRoot | Split-Path -Parent  # chess-engine/

if ($TunedParamsFile -eq "") {
    $TunedParamsFile = Join-Path $engineRoot "tuned_params.txt"
}
if (-not (Test-Path $TunedParamsFile)) {
    Write-Error "tuned_params.txt not found at: $TunedParamsFile"
    exit 1
}

if ($EngineCoreSrc -eq "") {
    $EngineCoreSrc = Join-Path $engineRoot "engine-core\src\main\java\coeusyk\game\chess\core\eval"
}
if ($EngineTunerSrc -eq "") {
    $EngineTunerSrc = Join-Path $engineRoot "engine-tuner\src\main\java\coeusyk\game\chess\tuner"
}

$evalPath     = Join-Path $EngineCoreSrc "Evaluator.java"
$pstPath      = Join-Path $EngineCoreSrc "PieceSquareTables.java"
$pawnPath     = Join-Path $EngineCoreSrc "PawnStructure.java"
$kingsafePath = Join-Path $EngineCoreSrc "KingSafety.java"
$evalParamsPath = Join-Path $EngineTunerSrc "EvalParams.java"

foreach ($f in $evalPath, $pstPath, $pawnPath, $kingsafePath, $evalParamsPath) {
    if (-not (Test-Path $f)) { Write-Error "Required file not found: $f"; exit 1 }
}

Write-Host "[apply-tuned-params] Source    : $TunedParamsFile"
Write-Host "[apply-tuned-params] Group     : $Group"
Write-Host ""

# ─── Parse tuned_params.txt ─────────────────────────────────────────────────
$raw = Get-Content $TunedParamsFile

function Get-Section([string]$header) {
    $inside = $false
    $result = @()
    foreach ($l in $raw) {
        if ($l -match "^## $header") { $inside = $true; continue }
        if ($l -match '^##' -and $inside) { break }
        if ($inside) { $result += $l }
    }
    return $result
}

# ─── MATERIAL ────────────────────────────────────────────────────────────────
function Apply-Material {
    $section = Get-Section 'MATERIAL \(MG, EG\)'
    $mat = @{}
    foreach ($l in $section) {
        if ($l -match '^(\w+)\s+MG=(-?\d+)\s+EG=(-?\d+)') {
            $mat[$Matches[1]] = @{ MG = [int]$Matches[2]; EG = [int]$Matches[3] }
        }
    }
    $pawn=$mat['Pawn']; $knight=$mat['Knight']; $bishop=$mat['Bishop']
    $rook=$mat['Rook']; $queen=$mat['Queen']

    Write-Host "[apply-tuned-params] MATERIAL: Pawn MG=$($pawn.MG) EG=$($pawn.EG) | Knight MG=$($knight.MG) EG=$($knight.EG) | Bishop MG=$($bishop.MG) EG=$($bishop.EG)"
    Write-Host "[apply-tuned-params]         : Rook MG=$($rook.MG) EG=$($rook.EG) | Queen MG=$($queen.MG) EG=$($queen.EG)"

    # --- Update Evaluator.java ---
    $lines = Get-Content $evalPath
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lines[$i] = $lines[$i] -replace '(MG_MATERIAL\[Piece\.Pawn\]\s*=\s*)(\d+);',   "`${1}$($pawn.MG);"
        $lines[$i] = $lines[$i] -replace '(EG_MATERIAL\[Piece\.Pawn\]\s*=\s*)(\d+);',   "`${1}$($pawn.EG);"
        $lines[$i] = $lines[$i] -replace '(MG_MATERIAL\[Piece\.Knight\]\s*=\s*)(\d+);', "`${1}$($knight.MG);"
        $lines[$i] = $lines[$i] -replace '(EG_MATERIAL\[Piece\.Knight\]\s*=\s*)(\d+);', "`${1}$($knight.EG);"
        $lines[$i] = $lines[$i] -replace '(MG_MATERIAL\[Piece\.Bishop\]\s*=\s*)(\d+);', "`${1}$($bishop.MG);"
        $lines[$i] = $lines[$i] -replace '(EG_MATERIAL\[Piece\.Bishop\]\s*=\s*)(\d+);', "`${1}$($bishop.EG);"
        $lines[$i] = $lines[$i] -replace '(MG_MATERIAL\[Piece\.Rook\]\s*=\s*)(\d+);',   "`${1}$($rook.MG);"
        $lines[$i] = $lines[$i] -replace '(EG_MATERIAL\[Piece\.Rook\]\s*=\s*)(\d+);',   "`${1}$($rook.EG);"
        $lines[$i] = $lines[$i] -replace '(MG_MATERIAL\[Piece\.Queen\]\s*=\s*)(\d+);',  "`${1}$($queen.MG);"
        $lines[$i] = $lines[$i] -replace '(EG_MATERIAL\[Piece\.Queen\]\s*=\s*)(\d+);',  "`${1}$($queen.EG);"
    }
    Set-Content $evalPath $lines
    Write-Host "[apply-tuned-params] Updated : Evaluator.java (material)"

    # --- Update EvalParams.extractFromCurrentEval() ---
    $ep = Get-Content $evalParamsPath
    for ($i = 0; $i -lt $ep.Count; $i++) {
        # p[0] = 100; p[1] = XX;    // Pawn
        $ep[$i] = $ep[$i] -replace '(p\[0\]\s*=\s*100;\s*p\[1\]\s*=\s*)(\d+);', "`${1}$($pawn.EG);"
        # p[2] = XX; p[3] = YY;     // Knight
        $ep[$i] = $ep[$i] -replace '(p\[2\]\s*=\s*)(\d+);\s*(p\[3\]\s*=\s*)(\d+);', "`${1}$($knight.MG); `${3}$($knight.EG);"
        # p[4] = XX; p[5] = YY;     // Bishop
        $ep[$i] = $ep[$i] -replace '(p\[4\]\s*=\s*)(\d+);\s*(p\[5\]\s*=\s*)(\d+);', "`${1}$($bishop.MG); `${3}$($bishop.EG);"
        # p[6] = XX; p[7] = YY;     // Rook
        $ep[$i] = $ep[$i] -replace '(p\[6\]\s*=\s*)(\d+);\s*(p\[7\]\s*=\s*)(\d+);', "`${1}$($rook.MG); `${3}$($rook.EG);"
        # p[8] = XX; p[9] = YY;     // Queen
        $ep[$i] = $ep[$i] -replace '(p\[8\]\s*=\s*)(\d+);\s*(p\[9\]\s*=\s*)(\d+);', "`${1}$($queen.MG); `${3}$($queen.EG);"
    }
    Set-Content $evalParamsPath $ep
    Write-Host "[apply-tuned-params] Updated : EvalParams.java (material baseline)"
}

# ─── PST ─────────────────────────────────────────────────────────────────────
function Apply-Pst {
    # Parse all 12 PST tables from tuned_params.txt  (a8=0 convention)
    $pstData = @{}
    $curName = $null
    $curRows = @()
    foreach ($l in $raw) {
        if ($l -match '^### (\w+) (MG|EG) PST') {
            if ($curName -ne $null) { $pstData[$curName] = $curRows }
            $curName = "$($Matches[1])_$($Matches[2])"
            $curRows = @()
        } elseif ($curName -ne $null -and $l -match '^\s+(-?\d+)') {
            # Collect 8 values per row
            $vals = [regex]::Matches($l, '-?\d+') | ForEach-Object { [int]$_.Value }
            $curRows += ,$vals
        }
    }
    if ($curName -ne $null) { $pstData[$curName] = $curRows }

    # Map tuned_params.txt piece names to PieceSquareTables array names
    $pieceMap = @{
        'PAWN'   = 'PAWN'
        'KNIGHT' = 'KNIGHT'
        'BISHOP' = 'BISHOP'
        'ROOK'   = 'ROOK'
        'QUEEN'  = 'QUEEN'
        'KING'   = 'KING'
    }
    $phases = @('MG', 'EG')

    $content = Get-Content $pstPath -Raw

    foreach ($piece in $pieceMap.Keys) {
        foreach ($phase in $phases) {
            $tunerKey = "${piece}_${phase}"
            if (-not $pstData.ContainsKey($tunerKey)) {
                Write-Warning "PST data missing for $tunerKey in tuned_params.txt"
                continue
            }
            $rows = $pstData[$tunerKey]   # 8 rows, each an array of 8 ints (a8=0 order)

            # Both PieceSquareTables.java and the tuner use a8=0 (index 0 = a8, rank 8 first).
            # No flip needed: tuned_params.txt row 0 → Java array row 0 (rank 8), etc.
            $javaRows = @()
            for ($r = 0; $r -le 7; $r++) {
                $javaRows += ,$rows[$r]
            }

            # Build the replacement array body
            $arrayBody = ""
            foreach ($row in $javaRows) {
                $formatted = ($row | ForEach-Object { "{0,6}," -f $_ }) -join ""
                $arrayBody += "    $formatted`r`n"
            }

            # Replace the array in PieceSquareTables.java
            # Pattern: static final int[] XX_YY = { ... };
            $arrayName = "${phase}_$($pieceMap[$piece])"
            $pattern = "(?s)(static final int\[\] ${arrayName} = \{)[^}]*(})"
            $replacement = "`${1}`r`n$arrayBody`${2}"
            $content = [regex]::Replace($content, $pattern, $replacement)
            Write-Host "[apply-tuned-params] Updated PST: $arrayName"
        }
    }
    Set-Content $pstPath $content -NoNewline
    Write-Host "[apply-tuned-params] Updated : PieceSquareTables.java"

    # EvalParams PST sync is omitted: EvalParams uses a8=0 internal layout
    # that the Adam optimizer works in. After applying to PieceSquareTables,
    # the PST tuning for the next round should start from the new java values.
    # TODO: flip and sync EvalParams PSTs if needed.
}

# ─── PAWN-STRUCTURE ───────────────────────────────────────────────────────────
function Apply-PawnStructure {
    $section = Get-Section 'PAWN STRUCTURE'
    $passedMgVals = $null
    $passedEgVals = $null
    $isolMg = $null; $isolEg = $null
    $dblMg  = $null; $dblEg  = $null
    foreach ($l in $section) {
        if ($l -match '^PASSED_MG indices 1-6:\s+(.+)') {
            $passedMgVals = ($Matches[1].Trim() -split '\s+') | ForEach-Object { [int]$_ }
        }
        if ($l -match '^PASSED_EG indices 1-6:\s+(.+)') {
            $passedEgVals = ($Matches[1].Trim() -split '\s+') | ForEach-Object { [int]$_ }
        }
        if ($l -match '^ISOLATED MG=(-?\d+)\s+EG=(-?\d+)') {
            $isolMg = [int]$Matches[1]; $isolEg = [int]$Matches[2]
        }
        if ($l -match '^DOUBLED\s+MG=(-?\d+)\s+EG=(-?\d+)') {
            $dblMg = [int]$Matches[1]; $dblEg = [int]$Matches[2]
        }
    }

    # Build PASSED_MG/EG arrays  (index 0 = 0 pinned, indices 1-6 from tuner, index 7 = 0 pinned)
    $mgArr = "0, $($passedMgVals -join ', '), 0"
    $egArr = "0, $($passedEgVals -join ', '), 0"

    Write-Host "[apply-tuned-params] PAWN STRUCTURE: PASSED_MG={$mgArr} | PASSED_EG={$egArr}"
    Write-Host "[apply-tuned-params]               : ISOLATED MG=$isolMg EG=$isolEg | DOUBLED MG=$dblMg EG=$dblEg"

    $lines = Get-Content $pawnPath
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lines[$i] = $lines[$i] -replace '(private static final int\[\] PASSED_MG = \{)[^}]*(};)', "`${1}$mgArr`};"
        $lines[$i] = $lines[$i] -replace '(private static final int\[\] PASSED_EG = \{)[^}]*(};)', "`${1}$egArr`};"
        $lines[$i] = $lines[$i] -replace '(private static final int ISOLATED_MG = )(\d+);', "`${1}$isolMg;"
        $lines[$i] = $lines[$i] -replace '(private static final int ISOLATED_EG = )(\d+);', "`${1}$isolEg;"
        $lines[$i] = $lines[$i] -replace '(private static final int DOUBLED_MG = )(\d+);', "`${1}$dblMg;"
        $lines[$i] = $lines[$i] -replace '(private static final int DOUBLED_EG = )(\d+);', "`${1}$dblEg;"
    }
    Set-Content $pawnPath $lines
    Write-Host "[apply-tuned-params] Updated : PawnStructure.java"

    # Sync EvalParams
    $ep = Get-Content $evalParamsPath
    $mgStr = "0, $($passedMgVals -join ', '), 0"
    $egStr = "0, $($passedEgVals -join ', '), 0"
    for ($i = 0; $i -lt $ep.Count; $i++) {
        $ep[$i] = $ep[$i] -replace '(int\[\] PASSED_MG = \{)[^}]*(};)', "`${1}$mgStr`};"
        $ep[$i] = $ep[$i] -replace '(int\[\] PASSED_EG = \{)[^}]*(};)', "`${1}$egStr`};"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ISOLATED_MG\] = )(\d+);', "`${1}$isolMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ISOLATED_EG\] = )(\d+);', "`${1}$isolEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_DOUBLED_MG\]\s*= )(\d+);', "`${1}$dblMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_DOUBLED_EG\]\s*= )(\d+);', "`${1}$dblEg;"
    }
    Set-Content $evalParamsPath $ep
    Write-Host "[apply-tuned-params] Updated : EvalParams.java (pawn-structure baseline)"
}

# ─── KING-SAFETY ─────────────────────────────────────────────────────────────
function Apply-KingSafety {
    $section = Get-Section 'KING SAFETY'
    $sh2=$null; $sh3=$null; $opf=$null; $hof=$null
    $atkN=$null; $atkB=$null; $atkR=$null; $atkQ=$null
    foreach ($l in $section) {
        if ($l -match '^SHIELD_RANK2=(\d+)\s+SHIELD_RANK3=(\d+)') {
            $sh2=[int]$Matches[1]; $sh3=[int]$Matches[2]
        }
        if ($l -match '^OPEN_FILE=(\d+)\s+HALF_OPEN_FILE=(\d+)') {
            $opf=[int]$Matches[1]; $hof=[int]$Matches[2]
        }
        if ($l -match '^ATTACKER_WEIGHTS\s+N=(-?\d+)\s+B=(-?\d+)\s+R=(-?\d+)\s+Q=(-?\d+)') {
            $atkN=[int]$Matches[1]; $atkB=[int]$Matches[2]; $atkR=[int]$Matches[3]; $atkQ=[int]$Matches[4]
        }
    }

    Write-Host "[apply-tuned-params] KING SAFETY: SHIELD R2=$sh2 R3=$sh3 | OPEN=$opf HALF=$hof | ATK N=$atkN B=$atkB R=$atkR Q=$atkQ"

    $lines = Get-Content $kingsafePath
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lines[$i] = $lines[$i] -replace '(private static final int SHIELD_RANK_2_BONUS = )(\d+);', "`${1}$sh2;"
        $lines[$i] = $lines[$i] -replace '(private static final int SHIELD_RANK_3_BONUS = )(\d+);', "`${1}$sh3;"
        $lines[$i] = $lines[$i] -replace '(private static final int OPEN_FILE_PENALTY = )(\d+);',   "`${1}$opf;"
        $lines[$i] = $lines[$i] -replace '(private static final int HALF_OPEN_FILE_PENALTY = )(\d+);', "`${1}$hof;"
        if ($atkN -ne $null) { $lines[$i] = $lines[$i] -replace '(ATTACKER_WEIGHT\[Piece\.Knight\] = )(-?\d+);', "`${1}$atkN;" }
        if ($atkB -ne $null) { $lines[$i] = $lines[$i] -replace '(ATTACKER_WEIGHT\[Piece\.Bishop\] = )(-?\d+);', "`${1}$atkB;" }
        if ($atkR -ne $null) { $lines[$i] = $lines[$i] -replace '(ATTACKER_WEIGHT\[Piece\.Rook\]\s*= )(-?\d+);', "`${1}$atkR;" }
        if ($atkQ -ne $null) { $lines[$i] = $lines[$i] -replace '(ATTACKER_WEIGHT\[Piece\.Queen\] = )(-?\d+);',  "`${1}$atkQ;" }
    }
    Set-Content $kingsafePath $lines
    Write-Host "[apply-tuned-params] Updated : KingSafety.java"

    $ep = Get-Content $evalParamsPath
    for ($i = 0; $i -lt $ep.Count; $i++) {
        $ep[$i] = $ep[$i] -replace '(p\[IDX_SHIELD_RANK2\]\s*= )(\d+);',   "`${1}$sh2;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_SHIELD_RANK3\]\s*= )(\d+);',   "`${1}$sh3;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_OPEN_FILE\]\s*= )(\d+);',      "`${1}$opf;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_HALF_OPEN_FILE\] = )(\d+);',   "`${1}$hof;"
        if ($atkN -ne $null) { $ep[$i] = $ep[$i] -replace '(p\[IDX_ATK_KNIGHT\]\s*= )(-?\d+);', "`${1}$atkN;" }
        if ($atkB -ne $null) { $ep[$i] = $ep[$i] -replace '(p\[IDX_ATK_BISHOP\]\s*= )(-?\d+);', "`${1}$atkB;" }
        if ($atkR -ne $null) { $ep[$i] = $ep[$i] -replace '(p\[IDX_ATK_ROOK\]\s*= )(-?\d+);',   "`${1}$atkR;" }
        if ($atkQ -ne $null) { $ep[$i] = $ep[$i] -replace '(p\[IDX_ATK_QUEEN\]\s*= )(-?\d+);',  "`${1}$atkQ;" }
    }
    Set-Content $evalParamsPath $ep
    Write-Host "[apply-tuned-params] Updated : EvalParams.java (king-safety baseline)"
}

# ─── MOBILITY ────────────────────────────────────────────────────────────────
function Apply-Mobility {
    $section = Get-Section 'MOBILITY \(MG then EG\)'
    $mgN=$null; $mgB=$null; $mgR=$null; $mgQ=$null
    $egN=$null; $egB=$null; $egR=$null; $egQ=$null
    foreach ($l in $section) {
        if ($l -match '^MG\s+N=(-?\d+)\s+B=(-?\d+)\s+R=(-?\d+)\s+Q=(-?\d+)') {
            $mgN=[int]$Matches[1]; $mgB=[int]$Matches[2]; $mgR=[int]$Matches[3]; $mgQ=[int]$Matches[4]
        }
        if ($l -match '^EG\s+N=(-?\d+)\s+B=(-?\d+)\s+R=(-?\d+)\s+Q=(-?\d+)') {
            $egN=[int]$Matches[1]; $egB=[int]$Matches[2]; $egR=[int]$Matches[3]; $egQ=[int]$Matches[4]
        }
    }

    Write-Host "[apply-tuned-params] MOBILITY: MG N=$mgN B=$mgB R=$mgR Q=$mgQ | EG N=$egN B=$egB R=$egR Q=$egQ"

    $lines = Get-Content $evalPath
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lines[$i] = $lines[$i] -replace '(MG_MOBILITY\[Piece\.Knight\] = )(-?\d+);', "`${1}$mgN;"
        $lines[$i] = $lines[$i] -replace '(MG_MOBILITY\[Piece\.Bishop\] = )(-?\d+);', "`${1}$mgB;"
        $lines[$i] = $lines[$i] -replace '(MG_MOBILITY\[Piece\.Rook\]\s*= )(-?\d+);', "`${1}$mgR;"
        $lines[$i] = $lines[$i] -replace '(MG_MOBILITY\[Piece\.Queen\] = )(-?\d+);',  "`${1}$mgQ;"
        $lines[$i] = $lines[$i] -replace '(EG_MOBILITY\[Piece\.Knight\] = )(-?\d+);', "`${1}$egN;"
        $lines[$i] = $lines[$i] -replace '(EG_MOBILITY\[Piece\.Bishop\] = )(-?\d+);', "`${1}$egB;"
        $lines[$i] = $lines[$i] -replace '(EG_MOBILITY\[Piece\.Rook\]\s*= )(-?\d+);', "`${1}$egR;"
        $lines[$i] = $lines[$i] -replace '(EG_MOBILITY\[Piece\.Queen\] = )(-?\d+);',  "`${1}$egQ;"
    }
    Set-Content $evalPath $lines
    Write-Host "[apply-tuned-params] Updated : Evaluator.java (mobility)"

    $ep = Get-Content $evalParamsPath
    for ($i = 0; $i -lt $ep.Count; $i++) {
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_MG_START\]\s*= )(-?\d+);',       "`${1}$mgN;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_MG_START \+ 1\] = )(-?\d+);',   "`${1}$mgB;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_MG_START \+ 2\] = )(-?\d+);',   "`${1}$mgR;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_MG_START \+ 3\] = )(-?\d+);',   "`${1}$mgQ;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_EG_START\]\s*= )(-?\d+);',       "`${1}$egN;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_EG_START \+ 1\] = )(-?\d+);',   "`${1}$egB;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_EG_START \+ 2\] = )(-?\d+);',   "`${1}$egR;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_MOB_EG_START \+ 3\] = )(-?\d+);',   "`${1}$egQ;"
    }
    Set-Content $evalParamsPath $ep
    Write-Host "[apply-tuned-params] Updated : EvalParams.java (mobility baseline)"
}

# ─── SCALARS ─────────────────────────────────────────────────────────────────
function Apply-Scalars {
    $section = Get-Section 'MISC TERMS'
    $tempo=$null; $bpMg=$null; $bpEg=$null
    $r7Mg=$null; $r7Eg=$null
    $rofMg=$null; $rofEg=$null
    $rsfMg=$null; $rsfEg=$null
    $knoMg=$null; $knoEg=$null
    $cpMg=$null; $cpEg=$null
    $bpwMg=$null; $bpwEg=$null
    $rbpMg=$null; $rbpEg=$null

    foreach ($l in $section) {
        if ($l -match '^TEMPO=(-?\d+)')                                { $tempo=[int]$Matches[1] }
        if ($l -match '^BISHOP_PAIR\s+MG=(-?\d+)\s+EG=(-?\d+)')       { $bpMg=[int]$Matches[1]; $bpEg=[int]$Matches[2] }
        if ($l -match '^ROOK_ON_7TH\s+MG=(-?\d+)\s+EG=(-?\d+)')       { $r7Mg=[int]$Matches[1]; $r7Eg=[int]$Matches[2] }
        if ($l -match '^ROOK_OPEN_FILE\s+MG=(-?\d+)\s+EG=(-?\d+)')    { $rofMg=[int]$Matches[1]; $rofEg=[int]$Matches[2] }
        if ($l -match '^ROOK_SEMI_OPEN\s+MG=(-?\d+)\s+EG=(-?\d+)')    { $rsfMg=[int]$Matches[1]; $rsfEg=[int]$Matches[2] }
        if ($l -match '^KNIGHT_OUTPOST\s+MG=(-?\d+)\s+EG=(-?\d+)')    { $knoMg=[int]$Matches[1]; $knoEg=[int]$Matches[2] }
        if ($l -match '^CONNECTED_PAWN\s+MG=(-?\d+)\s+EG=(-?\d+)')    { $cpMg=[int]$Matches[1]; $cpEg=[int]$Matches[2] }
        if ($l -match '^BACKWARD_PAWN\s+MG=(-?\d+)\s+EG=(-?\d+)')     { $bpwMg=[int]$Matches[1]; $bpwEg=[int]$Matches[2] }
        if ($l -match '^ROOK_BEHIND_PASSER\s+MG=(-?\d+)\s+EG=(-?\d+)') { $rbpMg=[int]$Matches[1]; $rbpEg=[int]$Matches[2] }
    }

    Write-Host "[apply-tuned-params] SCALARS: tempo=$tempo | bishopPair MG=$bpMg EG=$bpEg"
    Write-Host "[apply-tuned-params]        : rook7th MG=$r7Mg EG=$r7Eg | rookOpen MG=$rofMg EG=$rofEg"
    Write-Host "[apply-tuned-params]        : rookSemi MG=$rsfMg EG=$rsfEg | knightOutpost MG=$knoMg EG=$knoEg"
    Write-Host "[apply-tuned-params]        : connectedPawn MG=$cpMg EG=$cpEg | backwardPawn MG=$bpwMg EG=$bpwEg"
    Write-Host "[apply-tuned-params]        : rookBehindPasser MG=$rbpMg EG=$rbpEg"

    # Update Evaluator.java DEFAULT_CONFIG line by line
    $lines = Get-Content $evalPath
    for ($i = 0; $i -lt $lines.Count; $i++) {
        $lines[$i] = $lines[$i] -replace '(/\* tempo\s+\*/ )(-?\d+),',           "`${1}$tempo,"
        $lines[$i] = $lines[$i] -replace '(/\* bishopPairMg/Eg\s+\*/ )(-?\d+), (-?\d+),', "`${1}$bpMg, $bpEg,"
        $lines[$i] = $lines[$i] -replace '(/\* rook7thMg/Eg\s+\*/ )(-?\d+), (-?\d+),',    "`${1}$r7Mg, $r7Eg,"
        $lines[$i] = $lines[$i] -replace '(/\* rookOpenMg/Eg\s+\*/ )(-?\d+), (-?\d+),',   "`${1}$rofMg, $rofEg,"
        $lines[$i] = $lines[$i] -replace '(/\* rookSemiMg/Eg\s+\*/ )(-?\d+), (-?\d+),',   "`${1}$rsfMg, $rsfEg,"
        $lines[$i] = $lines[$i] -replace '(/\* knightOutpostMg/Eg\*/ )(-?\d+), (-?\d+),', "`${1}$knoMg, $knoEg,"
        $lines[$i] = $lines[$i] -replace '(/\* connectedPawnMg/Eg\*/ )(-?\d+), (-?\d+),', "`${1}$cpMg, $cpEg,"
        $lines[$i] = $lines[$i] -replace '(/\* backwardPawnMg/Eg \*/ )(-?\d+), (-?\d+),', "`${1}$bpwMg, $bpwEg,"
        $lines[$i] = $lines[$i] -replace '(/\* rookBehindMg/Eg\s+\*/ )(-?\d+), (-?\d+)',  "`${1}$rbpMg, $rbpEg"
    }
    Set-Content $evalPath $lines
    Write-Host "[apply-tuned-params] Updated : Evaluator.java (DEFAULT_CONFIG)"

    # Sync EvalParams scalars
    $ep = Get-Content $evalParamsPath
    for ($i = 0; $i -lt $ep.Count; $i++) {
        $ep[$i] = $ep[$i] -replace '(p\[IDX_TEMPO\]\s*= )(-?\d+);',              "`${1}$tempo;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_BISHOP_PAIR_MG\] = )(-?\d+);',       "`${1}$bpMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_BISHOP_PAIR_EG\] = )(-?\d+);',       "`${1}$bpEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_7TH_MG\]\s*= )(-?\d+);',        "`${1}$r7Mg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_7TH_EG\]\s*= )(-?\d+);',        "`${1}$r7Eg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_OPEN_FILE_MG\]\s*= )(-?\d+);',  "`${1}$rofMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_OPEN_FILE_EG\]\s*= )(-?\d+);',  "`${1}$rofEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_SEMI_OPEN_MG\]\s*= )(-?\d+);',  "`${1}$rsfMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_SEMI_OPEN_EG\]\s*= )(-?\d+);',  "`${1}$rsfEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_KNIGHT_OUTPOST_MG\]\s*= )(-?\d+);',  "`${1}$knoMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_KNIGHT_OUTPOST_EG\]\s*= )(-?\d+);',  "`${1}$knoEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_CONNECTED_PAWN_MG\]\s*= )(-?\d+);',  "`${1}$cpMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_CONNECTED_PAWN_EG\]\s*= )(-?\d+);',  "`${1}$cpEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_BACKWARD_PAWN_MG\]\s*= )(-?\d+);',   "`${1}$bpwMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_BACKWARD_PAWN_EG\]\s*= )(-?\d+);',   "`${1}$bpwEg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_BEHIND_PASSER_MG\] = )(-?\d+);',"`${1}$rbpMg;"
        $ep[$i] = $ep[$i] -replace '(p\[IDX_ROOK_BEHIND_PASSER_EG\] = )(-?\d+);',"`${1}$rbpEg;"
    }
    Set-Content $evalParamsPath $ep
    Write-Host "[apply-tuned-params] Updated : EvalParams.java (scalars baseline)"
}

# ─── Dispatch ────────────────────────────────────────────────────────────────
switch ($Group) {
    'material'       { Apply-Material }
    'pst'            { Apply-Pst }
    'pawn-structure' { Apply-PawnStructure }
    'king-safety'    { Apply-KingSafety }
    'mobility'       { Apply-Mobility }
    'scalars'        { Apply-Scalars }
    'all' {
        Apply-Material
        Apply-Pst
        Apply-PawnStructure
        Apply-KingSafety
        Apply-Mobility
        Apply-Scalars
    }
}

Write-Host ""
Write-Host "[apply-tuned-params] Done. Rebuild engine-uci JAR before SPRT:"
Write-Host "  .\mvnw.cmd package -pl engine-uci -am -DskipTests -q"
