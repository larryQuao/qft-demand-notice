# Converts a Microsoft Publisher file to PDF using Publisher COM automation.
# Safe to run while Publisher is open: Publisher COM runs in its own instance,
# and the script never closes an instance it did not start.
#
# Usage: pub_to_pdf.ps1 -PubPath <input.pub> -PdfPath <output.pdf>
param(
    [Parameter(Mandatory = $true)][string]$PubPath,
    [Parameter(Mandatory = $true)][string]$PdfPath
)

$ErrorActionPreference = 'Stop'

# Was Publisher already running? If so we must NOT Quit the application
# afterwards (that could close the user's documents).
$wasRunning = $false
try { $wasRunning = @(Get-Process MSPUB -ErrorAction SilentlyContinue).Count -gt 0 } catch { }

$pub = $null
$doc = $null

try {
    $pub = New-Object -ComObject Publisher.Application
    # Setting Visible on an already-running instance hangs some Office builds.
    if (-not $wasRunning) { try { $pub.Visible = $false } catch { } }

    # Publisher opens documents through the Application object (the
    # Documents collection has no Open method - unlike Word).
    $doc = $pub.Open($PubPath)

    # PDF export is Document.ExportAsFixedFormat(Format, FileName, ...) with
    # the format FIRST: pbFixedFormatTypePDF = 2.
    # (Document.SaveAs cannot produce PDF - its PbFileFormat enum has no PDF.)
    $doc.ExportAsFixedFormat(2, $PdfPath)
    Write-Host "Converted: $PdfPath"
}
catch {
    Write-Host "CONVERSION FAILED: $($_.Exception.Message)"
    exit 1
}
finally {
    if ($null -ne $doc) { try { $doc.Close() } catch { } }
    if ((-not $wasRunning) -and ($null -ne $pub)) { try { $pub.Quit() } catch { } }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
