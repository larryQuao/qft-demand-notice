# Creates a small .pub file containing the four token placeholders.
# Usage: create_probe_pub.ps1 -OutPath <file.pub>
param(
    [Parameter(Mandatory = $true)][string]$OutPath
)

$ErrorActionPreference = 'Stop'

# Was Publisher already running? If so we attach to that instance and must
# NOT Quit it afterwards (that would close the user's documents).
$wasRunning = $false
try { $wasRunning = @(Get-Process MSPUB -ErrorAction SilentlyContinue).Count -gt 0 } catch { }

$pub = $null
$doc = $null

try {
    $pub = New-Object -ComObject Publisher.Application
    if (-not $wasRunning) { try { $pub.Visible = $false } catch { } }

    # Publisher creates documents through the Application object.
    $doc = $null
    try { $doc = $pub.NewDocument() } catch { Write-Host "NewDocument failed: $($_.Exception.Message)" }

    $page = $doc.Pages.Item(1)
    $shape = $page.Shapes.AddTextbox(1, 50, 50, 500, 300)  # msoTextOrientationHorizontal
    $shape.TextFrame.TextRange.Text = "RE: DEMAND NOTICE`r`nLetter date: {{LETTER_DATE}}`r`nPeriod: {{PERIOD}}`r`nAmount: {{AMOUNT}}`r`nDeadline: {{DEADLINE}}"

    # pbFileFormatDocument = 1
    $doc.SaveAs($OutPath, 1)
    Write-Host "Created: $OutPath"
}
catch {
    Write-Host "CREATE FAILED: $($_.Exception.Message)"
    exit 1
}
finally {
    if ($null -ne $doc) { try { $doc.Close() } catch { } }
    if ((-not $wasRunning) -and ($null -ne $pub)) { try { $pub.Quit() } catch { } }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
