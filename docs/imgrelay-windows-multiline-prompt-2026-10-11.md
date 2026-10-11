# ImgRelay：Windows 启动脚本截断多行提示词的问题记录

记录日期：2026-10-11（Asia/Shanghai）  
用途：为 ImgRelay CLI 和 imgrelay skill 的优化提供可复现的缺陷、证据、修复建议与验收标准。

## 结论

在本机 Windows + PowerShell 环境中，通过 `imgrelay.cmd` 传入含真实换行的 `--prompt` 时，Node CLI 实际拿到的提示词只剩第一行，位于提示词之后的选项也丢失。命令仍可能退出成功并返回图片，因此仅凭退出码或“图片已保存”无法发现参数传递错误。

本次 Logo 生成中，A1 的预期提示词有 3001 个字符，实际记录只有第一句的 82 个字符。角色、颜色、构图和约束全部未进入记录；`--size`、`--output-dir`、`--filename`、`--json` 等后续参数没有生效。于是返回了与用户要求无关的图片，并保存到默认目录。

直接调用 Node 可执行文件和 ImgRelay 的 JavaScript 入口，使用参数数组且 `shell: false`，已验证能完整保留多行提示词及后续选项。随后逐张完成了六张图片，六次生成记录的提示词均与原始字符串完全一致。

## 环境与原始调用

| 项目 | 现场值 |
| --- | --- |
| 操作系统 | Windows，PowerShell 报告 `Microsoft Windows 10.0.26300` |
| PowerShell | 7.6.5 |
| `$PSNativeCommandArgumentPassing` | `Windows` |
| Node.js | v24.21.0 |
| ImgRelay CLI | 0.2.0 |
| `Get-Command imgrelay` | `Application`，指向 `C:\Users\Lenovo\.vite-plus\bin\imgrelay.cmd` |
| 供应商 | `pqh`，本机默认项 |
| 请求模型 | `gpt-image-2.5-flare`，本机默认项；上游没有单独报告底层模型 |

原始调用形式：

```powershell
$taskBrief = Get-Content -LiteralPath 'D:\code\TerrariaToolbox\output\imagegen\terraria-logo-20261011-141750\generation-brief.json' -Raw | ConvertFrom-Json
$taskCandidate = $taskBrief.variants | Where-Object { $_.label -eq 'A1' }

imgrelay generate --prompt $taskCandidate.prompt --provider pqh --model gpt-image-2.5-flare --size 1536x1536 --n 1 --output-dir $taskBrief.outputDir --filename $taskCandidate.filename --json
```

这里的 `prompt` 是完整的多行字符串；没有主动拆分或取第一行。

现场启动链：

```text
PowerShell
  → C:\Users\Lenovo\.vite-plus\bin\imgrelay.cmd
  → C:\Users\Lenovo\.vite-plus\js_runtime\node\24.21.0\imgrelay.cmd
  → node.exe ...\node_modules\imgrelay\dist\index.mjs
```

外层脚本：

```bat
@echo off
"C:\Users\Lenovo\.vite-plus\js_runtime\node\24.21.0\imgrelay.cmd" %*
exit /b %ERRORLEVEL%
```

内层 npm 风格脚本解析 Node 路径后，通过以下形式转发参数：

```bat
"%_prog%" "%dp0%\node_modules\imgrelay\dist\index.mjs" %*
```

## 现场证据

A1 的调用前提示词与错误记录中的提示词：

```text
预期长度：3001
实际长度：82
实际内容：
Create one complete full-bleed 1:1 square image, approximately 1536 x 1536 pixels.
```

| 项目 | 首次 A1 | 修正后的 A1 |
| --- | --- | --- |
| 请求 ID | `d4d6550e-7a19-4323-9da9-71a43060ddbe` | `84269640-a793-42fa-943c-94bf5d663624` |
| 开始时间（北京时间） | 2026-10-11 14:19:40 | 2026-10-11 14:27:18 |
| 记录中的提示词长度 | 82 | 3001 |
| `parameters` | `{"n":1}` | `{"size":"1536x1536","n":1}` |
| 实际尺寸 | 1254 × 1254 | 1536 × 1536 |
| 实际保存目录 | 默认 `output/imagegen/` | 指定的批次目录 |
| 实际文件名 | 请求 ID + `-01.png` | 指定的 A1 文件名 |
| 标准输出形式 | 裸结果对象 | 含 `schemaVersion/command/status/data/error` 的 JSON |
| 上游报告的输入 token | 24 | 593 |

token 数量是辅助证据；定位主要依据提示词记录、参数记录及离线 argv 探针。

首次 A1、A2、B1、B2 的记录均只有相同的第一句。首次四次请求返回三张图片；A2 返回 HTTP 400，未返回图片。A2 的上游错误文本提到内容防护，但现场证据不足以解释这个拒绝原因，也不能将其归因为并发限制。

用户随后明确授权重新生成 A、B 四张，并要求上一张结束后再生成下一张。修正后的 A1、A2、B1、B2、C1、C2 六次均成功。整个过程共提交十次生成请求、保存九张原图；本次记录问题时只做离线探针，没有再次提交生图请求。

其他需要注意的现象：

- `--provider` 和 `--model` 丢失后恰好仍沿用同名默认项，容易让人误以为选项已成功传递。
- `--n 1` 与默认数量相同，单凭实际张数也看不出这个选项是否到达。
- `--json` 丢失会改变输出结构，影响依赖 `data.images` 的自动化读取。
- 本次首次图片为 1254 × 1254，不能据此判定供应商存在该尺寸上限：当时 `--size` 已丢失。
- 退出成功只说明进程处理完实际收到的参数，不能说明实际参数与调用者预期一致。

## 离线复现：无需供应商、密钥或生成请求

在现场环境中，用只打印 `process.argv` 的 Node 脚本替换 ImgRelay，即可复现。以下脚本会创建独立临时目录；不会联网或调用生图接口。

```powershell
# PowerShell 7；NodeExecutable 必须是 .exe，避免再次选择 .cmd。
$taskNodeExecutable = 'C:\Users\Lenovo\.vite-plus\js_runtime\node\24.21.0\node.exe'
$taskProbeDir = Join-Path ([IO.Path]::GetTempPath()) ('imgrelay-argv-repro-' + [guid]::NewGuid())
New-Item -ItemType Directory -Path $taskProbeDir | Out-Null

@'
const args = process.argv.slice(2);
const promptIndex = args.indexOf('--prompt');
const prompt = promptIndex >= 0 ? args[promptIndex + 1] : null;
console.log(JSON.stringify({
  args,
  prompt,
  promptCharacters: prompt?.length ?? null,
  hasSize: args.includes('--size'),
  hasJson: args.includes('--json')
}));
'@ | Set-Content -LiteralPath (Join-Path $taskProbeDir 'argv-probe.mjs') -Encoding utf8

$taskInnerScript = "@echo off`r`n" +
    '"' + $taskNodeExecutable + '" "%~dp0argv-probe.mjs" %*' + "`r`n"
$taskInnerScript | Set-Content -LiteralPath (Join-Path $taskProbeDir 'inner.cmd') -Encoding utf8

@'
@echo off
"%~dp0inner.cmd" %*
exit /b %ERRORLEVEL%
'@ | Set-Content -LiteralPath (Join-Path $taskProbeDir 'outer.cmd') -Encoding utf8

$taskPrompt = "Line one: square image.`nLine two: blue slime.`nLine three: sage background."
$taskPrompt.Length # 74

# 有问题的 .cmd 路径
& (Join-Path $taskProbeDir 'outer.cmd') --prompt $taskPrompt --size 1536x1536 --json

# 对照：直接运行 .exe 和 JS 入口
& $taskNodeExecutable (Join-Path $taskProbeDir 'argv-probe.mjs') --prompt $taskPrompt --size 1536x1536 --json
```

实测输出：

```json
{
  "wrapped": {
    "args": ["--prompt", "Line one: square image."],
    "promptCharacters": 23,
    "hasSize": false,
    "hasJson": false
  },
  "direct": {
    "args": [
      "--prompt",
      "Line one: square image.\nLine two: blue slime.\nLine three: sage background.",
      "--size",
      "1536x1536",
      "--json"
    ],
    "promptCharacters": 74,
    "hasSize": true,
    "hasJson": true
  }
}
```

上述两条路径的进程退出码均为 0。

额外离线边界测试：

| 输入与启动方式 | 预期字符数 | 实际字符数 | 提示词完整 | 后续 size/json |
| --- | ---: | ---: | --- | --- |
| 单行提示词，经两层 .cmd | 74 | 74 | 是 | 保留 |
| LF 三行提示词，经单层 .cmd | 74 | 23 | 否 | 丢失 |
| LF 三行提示词，经两层 .cmd | 74 | 23 | 否 | 丢失 |
| CRLF 三行提示词，经两层 .cmd | 76 | 23 | 否 | 丢失 |
| LF 三行，size/json 放在 prompt 前 | 74 | 23 | 否 | 保留 |
| LF 三行，直接 Node .exe | 74 | 74 | 是 | 保留 |

把其他选项放在 `--prompt` 前面只能保住前面的选项，不能解决提示词截断。把换行替换为空格在这个简单样例中可传递，但会改变输入，也没有验证特殊字符与长文本行为，不应作为通用修复。

## 定位范围与尚未验证的事项

已定位到 Windows 的 `.cmd`／命令行启动路径：用不包含 ImgRelay、Commander 或 HTTP 请求的 argv 探针也能复现；直接 Node 入口则正常。单层 `.cmd` 即可触发，说明问题不依赖两层包装同时存在。

尚未逐层捕获 PowerShell 的原生命令行、Windows 进程创建参数以及 `cmd.exe` 内部解析结果。因此，可以确认“经该启动路径后 argv 已被截断”，但不能据此声称已找到某一行 npm 包装脚本或 Commander 实现中的具体缺陷。

目前没有证据证明供应商存在并发限制。本次没有对 PowerShell 5.1、其他 PowerShell 参数传递模式、其他 Windows 安装器或其他操作系统做覆盖测试。

## 已验证的临时绕过方式

直接运行已安装 ImgRelay 的 Node 入口，用独立参数数组调用，保持 `shell: false` 和 `windowsHide: true`。程序继续使用 ImgRelay 的配置、鉴权、HTTP 调用、保存与记录能力。

直接 Node 调用解决了本次换行传递问题，仍受操作系统命令行长度限制。超长提示词应通过文件或 stdin 输入；不要将这个临时绕过方式视为长文本输入的完整替代。

现场采用的关键调用形式：

```javascript
import { spawn } from 'node:child_process';

const nodeExecutable =
  'C:/Users/Lenovo/.vite-plus/js_runtime/node/24.21.0/node.exe';
const cliEntry =
  'C:/Users/Lenovo/.vite-plus/js_runtime/node/24.21.0/node_modules/imgrelay/dist/index.mjs';

// prompt 是原始多行字符串；下列变量来自调用者准备的任务参数。
const args = [
  cliEntry, 'generate',
  '--prompt', prompt,
  '--provider', provider,
  '--model', model,
  '--size', '1536x1536',
  '--n', '1',
  '--output-dir', outputDir,
  '--filename', filename,
  '--json'
];

const child = spawn(nodeExecutable, args, {
  shell: false,
  windowsHide: true,
  stdio: ['ignore', 'pipe', 'pipe']
});
```

这些绝对路径是现场安装路径，不能直接写成所有用户环境的固定路径。通用实现应先定位本机 Node 可执行文件和已安装的 ImgRelay 入口。

现场封装额外保存了 stdout、stderr、退出码和生成记录路径，并在请求结束后检查：

```javascript
record.prompt === originalPrompt
```

六次检查均为 `true`。这项检查用于发现参数传递问题；它不是对生成图片的审美、颜色或构图合规性评分。

## CLI 优化建议

### P0：提供不依赖命令行承载长文本的输入方式

以下接口是建议新增的功能，当前 0.2.0 没有这些入口：

- `--prompt-file <path>`：以明确的 UTF-8 编码读取文件；只把路径放入命令行。
- stdin 入口，例如 `--prompt-stdin`：通过流读取原始文本，避免 Windows 命令行长度与换行限制。
- 将 `--prompt`、文件和 stdin 输入设为互斥；多入口同时出现时明确报错，避免隐式覆盖。
- 保留提示词的 LF/CRLF、首尾空白、空行、制表符和 Unicode；不要默默 trim、合并换行或重新格式化。明确约定 UTF-8 BOM 的处理方式。

仅在 Node CLI 内改参数解析器，无法恢复启动脚本已经丢掉的文本；应同时处理启动入口或增加文件／流输入。

### P0：提供提交前的有效请求预览

建议新增不联网、不提交生成的 `generate --dry-run --json` 或等价能力，输出：

- CLI 实际解析到的提示词字符数、UTF-8 字节数和 SHA-256。
- 实际解析到的参数、解析后的供应商／模型及其来源（显式参数或默认值）。
- 解析后的输出目录、文件名和本次是否会提交网络请求。
- 稳定的结构化结果，方便调用方与预期参数比较。

生成记录也可增加 `promptSha256`、`promptCharacters`、`promptUtf8Bytes` 与输出参数来源。调用者应在发送前用原始字符串计算同一摘要，才能发现“第一行仍是合法非空字符串”的静默错误。

预览本身同样可能被启动脚本截断，因此不能仅检查“预览命令成功”；应比较预期摘要、长度及关键选项。若后续预览标志可能被吞，应优先使用文件／流入口或安全的直接 Node 启动方式。

### P1：定位与输出稳定性

- 提供本机运行时诊断：实际 launcher、Node 可执行文件、CLI JS 入口、CLI 版本，以及文件／stdin／预览能力。该诊断应能离线运行。
- 继续保持 JSON 标准输出与进度 stderr 分离；明确无 `--json` 时的输出形态。
- 提供可用的 Windows 安全调用示例，避免把 JS 参数数组转成 shell 命令字符串。
- 将原始输入来源、有效参数与保存路径写入记录，减少用默认项“掩盖丢参”的情况。
- 支持调用方按供应商约束串行执行；本次逐张生成是用户明确要求，不能默认把六张候选直接并发提交。

## imgrelay skill 优化建议

当前 skill 要求长提示词仍通过 `--prompt` 传递，并指出没有文件或 stdin 入口。这在当前 CLI 能力下属实，但缺少 Windows `.cmd` 启动路径的专门处理。

建议加入以下规则：

1. **先判断启动入口。** Windows 下执行 `Get-Command imgrelay`；如果入口为 `.cmd/.bat` 且提示词含真实换行，先使用已验证的安全入口，不直接提交生图。
2. **按已安装版本的真实能力选择输入。** CLI 支持文件／stdin 后优先使用；旧版可通过 Node 可执行文件与已安装的 CLI JS 入口，使用独立参数数组、`shell: false`。不能假定新选项已可用。
3. **提交前做一次离线参数核对。** 优先用官方预览能力；旧版用无网络 argv 探针验证所选启动路径。核对完整提示词和 size、n、provider、model、output-dir、filename、json 等选项。
4. **区分调用错误与绘图质量。** 若记录中的提示词不匹配，说明调用未完整表达用户需求，不能归因为模型不理解提示词，也不能靠换供应商或多抽几张处理。
5. **保留结果并解释追加请求。** 元数据不匹配时先保留原图和记录、停止尚未提交的同类错误调用；已经提交的请求继续跟踪。需要重新生成时，按现有 skill 要求由用户明确决定，避免未经授权追加请求。
6. **首次正确调用后核对记录。** 在同一启动路径的批次中，先确认首个请求的完整提示词和关键选项，再提交其余请求。不要检查图片外观后自动重抽。
7. **尊重串行要求。** 用户要求逐张生成时，上一请求结束后再提交下一请求；不要把“支持子代理”解释为必须并发调用供应商。
8. **提示词原样传递。** 不使用 `JSON.stringify()` 作为 shell 引用，不通过手动删换行来掩盖问题，不用 shell 字符串拼接承载完整提示词。

可直接加入 skill 的段落草案：

> Windows 下先检查 imgrelay 的启动入口。若入口为 .cmd/.bat，且提示词包含真实换行，不要直接通过该入口提交 --prompt。优先使用本机 CLI 已支持的提示词文件或 stdin 入口；未提供这些入口时，定位 Node 可执行文件和已安装的 ImgRelay JS 入口，用参数数组、shell: false 调用。提交前以不联网的方式核对完整提示词及关键参数；生成后比较记录中的提示词与原始字符串。发现不匹配时保留所有已返回结果，解释调用问题，不自动追加生成请求。

## 建议回归与验收标准

CLI 修改后，至少覆盖以下输入与环境组合；除上面的现场用例外，下列扩展组合尚未在本次验证：

| 维度 | 建议覆盖 |
| --- | --- |
| 换行 | 单行、LF、CRLF、空行、首尾换行 |
| 内容 | 中文、emoji、单双引号、反斜杠、美元符号、反引号、百分号、&、管道、括号 |
| 长度 | 超过 8191 字符的提示词，验证文件／stdin 路径 |
| 参数位置 | prompt 在首／中／尾，关键选项位于 prompt 前后 |
| 输入来源 | 字符串、UTF-8 文件、stdin；冲突入口与空输入 |
| Windows 启动方式 | PowerShell 7、PowerShell 5.1、cmd.exe、npm／安装器 shim、直接 Node |
| 进程 API | 参数数组且 shell=false；确认是否错误转回 shell 字符串 |
| 本地与网络 | 首先用 argv 探针和 mock HTTP，不依赖付费生图来做参数回归 |

验收应包括：

- CLI 输入与调用者原始字符串一致，提示词摘要、字符数、字节数匹配。
- 所有后续选项完整到达，显式参数不会无声回退到默认值。
- 文件和 stdin 入口能保留约定的原始文本，并支持长提示词。
- 预览不联网、不提交生成；输入或能力校验失败也不提交请求。
- JSON 可稳定解析，进度输出不混入 stdout。
- mock HTTP 捕获到的 prompt 与预期完全一致，不只验证 CLI 本地记录。
- skill 不虚构当前版本没有的输入接口；不自动修改提示词或追加生图。
- 并发／串行策略与用户指令及供应商约束一致。

## 本地证据与交接文件

以下路径为本机证据。`output/` 被项目 Git 忽略；对外提交问题时，本文已保留关键摘录，若需要完整原件，应另行附上对应文件。

- [原始任务提示词与配色](../output/imagegen/terraria-logo-20261011-141750/generation-brief.json)
- [首次 A1 生成记录](../output/imagegen/d4d6550e-7a19-4323-9da9-71a43060ddbe-report.json)
- [首次 A2 拒绝记录](../output/imagegen/8bedd4cc-41d2-4f19-9a01-f81bd30ebbd6-report.json)
- [修正后的 A1 生成记录](../output/imagegen/terraria-logo-20261011-141750/84269640-a793-42fa-943c-94bf5d663624-report.json)
- [已验证的直接 Node 封装](../output/imagegen/terraria-logo-20261011-141750/generate-candidate.mjs)
- [完整批次交付清单](../output/imagegen/terraria-logo-20261011-141750/delivery-manifest.json)
- [离线探针结果](../output/imgrelay-windows-argv-repro/reproduction-results.json)
- [离线边界测试结果](../output/imgrelay-windows-argv-repro/boundary-results.json)

原技能文件：`C:\Users\Lenovo\.agents\skills\imgrelay\SKILL.md`。本次只记录问题与优化建议，没有修改 CLI 安装、全局配置或 skill。
