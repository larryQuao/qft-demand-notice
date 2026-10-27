# Auto-inserts {{...}} placeholders into a Microsoft Publisher .pub file by
# recognising the hard-coded values it already contains.
#
# The caller copies the ORIGINAL .pub to -PubPath first; this script opens that
# copy in place (Document.Save keeps the same file and format), so the user's
# original file is never touched.
#
# Recognised (all optional, best effort, reported per field):
#   {{AMOUNT}}      e.g. "GHS 2,345.16"
#   {{PERIOD}}      e.g. "September, 2026" after "for the month of"
#   {{DEADLINE}}    e.g. "14th October, 2026" after "not later than"
#   {{LETTER_DATE}} e.g. "5th October, 2026" (first remaining date)
#
# IMPORTANT: each replacement is computed on FRESH story text and applied
# immediately. Publishing text mutations invalidate character positions, so
# spans must never be gathered up-front and applied later.
#
# Usage: tokenize_pub.ps1 -PubPath <file.pub>
param(
    [Parameter(Mandatory = $true)][string]$PubPath
)

$ErrorActionPreference = 'Stop'

# Was Publisher already running? If so we must NOT Quit the application
# afterwards (that could close the user's documents).
$wasRunning = $false
try { $wasRunning = @(Get-Process MSPUB -ErrorAction SilentlyContinue).Count -gt 0 } catch { }

$pub = $null
$doc = $null

# Ordinal day + full month name + 4-digit year, e.g. "5th October, 2026".
$dayOrd  = '(?<day>\d{1,2})\s*(?<ord>st|nd|rd|th)\s+(?<month>[A-Za-z]+)\s*,?\s*(?<year>\d{4})'
# Month name + 4-digit year (no ordinal), e.g. "September, 2026".
$monYear = '(?<month>[A-Za-z]+)\s*,?\s*(?<year>\d{4})'
$amount  = 'GHS\s*\d[\d,]*(?:\.\d{1,2})?'
$months  = @('january','february','march','april','may','june','july',
             'august','september','october','november','december')

$replaced = New-Object System.Collections.Generic.List[string]
$missed   = New-Object System.Collections.Generic.List[string]

function Test-MonthName([string]$s) {
    return $months -contains $s.ToLowerInvariant()
}

# Replaces the matched span with the token, then returns the fresh story text.
# Positions are only valid against the text object the match came from, which
# is why every field fetches a fresh $text right before its match.
function Replace-Match([object]$range, [string]$token,
                       [System.Text.RegularExpressions.Capture]$capture) {
    # Pad the token with spaces so the placeholder reserves about the width of
    # the value it replaces (average-glyph estimate). That keeps the letter's
    # own line wrapping intact and leaves the stamped value room to fit at the
    # body's own font size - the stamps never need to shrink the text.
    $origEm  = $capture.Length * 0.48   # Calibri average glyph ~0.48 em
    $tokenEm = $token.Length * 0.60    # "{{...}}" is wide, ~0.60 em per char
    $pad = [Math]::Max(0, [int][Math]::Round(($origEm - $tokenEm) / 0.26))
    $replacement = $token + (' ' * $pad)
    $sub = $range.Characters($capture.Index + 1, $capture.Length)
    $sub.Text = $replacement
    return $range.Text
}

# All month-validated date matches of the current text.
function All-Dates($text) {
    return @([regex]::Matches($text, $dayOrd) |
        Where-Object { Test-MonthName $_.Groups['month'].Value })
}

try {
    $pub = New-Object -ComObject Publisher.Application
    if (-not $wasRunning) { try { $pub.Visible = $false } catch { } }

    $doc = $pub.Open($PubPath)

    foreach ($story in $doc.Stories) {
        $range = $story.TextRange
        $text  = $range.Text
        if ([string]::IsNullOrWhiteSpace($text)) { continue }
        # Normalise NBSP to a plain space (1:1, so indexes are unchanged).
        $text = $text.Replace([char]0x00A0, ' ')
        if ($text.Contains('{{')) {
            # The document already carries placeholders - do not add duplicates.
            Write-Host "QFT_AUTO_TOKENIZE replaced= missed= alreadyTokenized=true"
            exit 0
        }

        # ---- {{AMOUNT}}: first amount-looking span ------------------------
        $ma = [regex]::Match($text, $amount)
        if ($ma.Success) {
            $text = Replace-Match $range '{{AMOUNT}}' $ma
            $replaced.Add('AMOUNT')
        } else {
            $missed.Add('AMOUNT')
        }

        # ---- {{DEADLINE}}: date after a due phrasing ----------------------
        $deadlineDone = $false
        foreach ($pat in @(
                "(?<pre>not later than\s+)(?<date>$dayOrd)",
                "(?<pre>due\s+(?:on|by)\s+)(?<date>$dayOrd)",
                "(?<pre>on or before\s+)(?<date>$dayOrd)")) {
            $md = [regex]::Match($text, $pat)
            if ($md.Success) {
                $d = $md.Groups['date']
                $text = Replace-Match $range '{{DEADLINE}}' $d
                $replaced.Add('DEADLINE')
                $deadlineDone = $true
                break
            }
        }
        if (-not $deadlineDone) {
            # Confidence fallback: the deadline is the LAST date in the letter
            # (the letter date is the first). Only with >= 2 dates is that a
            # reliable guess; otherwise leave the letter untouched there.
            $dates = All-Dates $text
            if ($dates.Count -ge 2) {
                $text = Replace-Match $range '{{DEADLINE}}' $dates[$dates.Count - 1]
                $replaced.Add('DEADLINE')
            } else {
                $missed.Add('DEADLINE')
            }
        }

        # ---- {{PERIOD}}: month-year after "for the month of" --------------
        $mp = [regex]::Match($text, "(?<pre>for the month of\s+)(?<my>$monYear)")
        if ($mp.Success -and (Test-MonthName $mp.Groups['month'].Value)) {
            $my = $mp.Groups['my']
            $text = Replace-Match $range '{{PERIOD}}' $my
            $replaced.Add('PERIOD')
        } else {
            $missed.Add('PERIOD')
        }

        # ---- {{LETTER_DATE}}: first date still standing --------------------
        $dates = All-Dates $text
        if ($dates.Count -ge 1) {
            $text = Replace-Match $range '{{LETTER_DATE}}' $dates[0]
            $replaced.Add('LETTER_DATE')
        } else {
            $missed.Add('LETTER_DATE')
        }

        # Only the first story (the letter body) is tokened: a demand notice
        # shows each value once. Remaining stories (tables, footers) are left
        # untouched so we never stamp the same value into the wrong place.
        break
    }

    $doc.Save()
    $r = $replaced -join ','
    $m2 = $missed -join ','
    Write-Host "QFT_AUTO_TOKENIZE replaced=$r missed=$m2 alreadyTokenized=false"
}
catch {
    Write-Host "TOKENIZE FAILED: $($_.Exception.Message)"
    exit 1
}
finally {
    if ($null -ne $doc) { try { $doc.Close() } catch { } }
    if ((-not $wasRunning) -and ($null -ne $pub)) { try { $pub.Quit() } catch { } }
    [GC]::Collect()
    [GC]::WaitForPendingFinalizers()
}
