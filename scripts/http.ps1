# curl.exe is bundled with Windows 10/11. It also works in environments where
# PowerShell's HTTP cmdlets inherit incompatible proxy settings.
function Invoke-LocalApi {
    param(
        [Parameter(Mandatory=$true)][string]$Uri,
        [string]$Method='GET',
        [System.Collections.IDictionary]$Headers=@{},
        [string]$Body,
        [string]$ContentType='application/json',
        [int]$TimeoutSec=30
    )
    $curlArgs = @('--silent','--fail','--max-time',"$TimeoutSec",'--request',$Method.ToUpperInvariant(),$Uri)
    foreach ($entry in $Headers.GetEnumerator()) { $curlArgs += @('--header',"$($entry.Key): $($entry.Value)") }
    if ($PSBoundParameters.ContainsKey('Body')) {
        # stdin preserves JSON quotes with both Windows PowerShell and pwsh.
        $response = $Body | & curl.exe @curlArgs --header "Content-Type: $ContentType" --data-binary '@-'
    } else {
        $response = & curl.exe @curlArgs
    }
    if ($LASTEXITCODE -ne 0) { throw "Local HTTP request failed: $Method $Uri (curl exit $LASTEXITCODE)" }
    if ($response) { return ($response -join "`n" | ConvertFrom-Json) }
}
