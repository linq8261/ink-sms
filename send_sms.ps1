<#
.SYNOPSIS
    发送一条模拟短信到正在运行的模拟器（无需指定设备）。
.DESCRIPTION
    通过 adb 的 emulator 控制台发送一条短信，短信内容带有精确到秒的格式化时间。
    前提：
      1) 已启动一个模拟器（且当前只连了这一个设备，adb 会自动选中，无需 -s）；
      2) 本 APP 已设为默认短信应用，才能通过 SMS_DELIVER 正常接收并落库。
#>
param(
    [string]$From = "10086",       # 发件人号码
#    [string]$Text = "这是一条模拟短信"  # 短信正文（不含时间，脚本会自动拼接时间）
#    [string]$Text = "【中国电信】尊敬的客户，您已成功充值3元，邀您参与限量权益领取活动，另有更多权益好物等你领~点击（https://a.189.cn/Jecpch）即可参与。回复TD将不再接受此类消息"  # 短信正文（不含时间，脚本会自动拼接时间）
    [string]$Text = "【百度】验证码：954273（有效期30分钟）。您正在使用注册功能，验证码提供他人可能导致百度账号被盗，请勿转发或泄漏。"  # 短信正文（不含时间，脚本会自动拼接时间）
)

# 精确到秒的格式化时间
$ts = Get-Date -Format 'yyyy-MM-dd HH:mm:ss'
$message = "$Text [$ts]"

Write-Host "[$ts] 正在向模拟器发送模拟短信 -> 发件人=$From  内容=$message"
& adb -s emulator-5554 emu sms send $From $message

if ($LASTEXITCODE -eq 0) {
    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] 发送成功。"
} else {
    Write-Host "[$(Get-Date -Format 'yyyy-MM-dd HH:mm:ss')] 发送失败，请确认：" -ForegroundColor Red
    Write-Host "  1) 模拟器已启动且为当前唯一设备；" -ForegroundColor Red
    Write-Host "  2) adb 已加入系统 PATH；" -ForegroundColor Red
    Write-Host "  3) 本 APP 已设为默认短信应用。" -ForegroundColor Red
}
