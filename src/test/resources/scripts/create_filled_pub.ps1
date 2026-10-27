# Creates a small .pub that is a FILLED-IN letter (no {{...}} tokens), the way
# real-world Publisher letters arrive. The auto-tokenizer must recognise the
# date, amount, period and deadline inside it.
# Usage: create_filled_pub.ps1 -OutPath <file.pub>
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

    $doc = $null
    try { $doc = $pub.NewDocument() } catch { Write-Host "NewDocument failed: $($_.Exception.Message)" }

    $page = $doc.Pages.Item(1)
    $shape = $page.Shapes.AddTextbox(1, 50, 50, 500, 310)  # msoTextOrientationHorizontal
    $shape.TextFrame.TextRange.Text =
        "5th October, 2026`r`n" +
        "The Head of Finance,`r`n" +
        "Dear Madam,`r`n" +
        "DEMAND LETTER`r`n" +
        "Please be informed that the amount of GHS 2,345.16 for the month of " +
        "September, 2026 is due not later than 14th October, 2026. You are " +
        "required to pay earlier to avoid penalties.`r`n" +
        "Yours Faithfully,`r`n" +
        "Thomas D. Appiah"

    $doc.SaveAs($OutPath)
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
