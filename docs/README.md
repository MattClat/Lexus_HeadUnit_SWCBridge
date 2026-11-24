# SWC Bridge — docs

The story of how SWC Bridge was made to start itself after a cold boot, with every finding along the way.

| Doc | What it is |
|---|---|
| [SwcBridge-ColdBoot-Findings.md](SwcBridge-ColdBoot-Findings.md) | **Start here.** Final status (Oct 2026), how the fix works, known limitations, and the full investigation record. |
| [TESTING.md](TESTING.md) | Testing at the desk with the mock Poweramp, and the verified desk and car results (5 Sep 2026). |
| [volume-story.md](volume-story.md) | Why Android's volume wouldn't go past 10/15 (the safe-volume limiter), and the fix (v0.4 – v0.6). |
| [../CHANGELOG.md](../CHANGELOG.md) | What changed in each version, v0.1 to v0.9.2. |
| [infographics/how-swc-bridge-starts-itself.png](infographics/how-swc-bridge-starts-itself.png) | One-page overview: the head unit's switch-off rule, before vs. after, a wheel press, a drive timeline. |
| [infographics/lean-mode-explained.png](infographics/lean-mode-explained.png) | Deep dive into lean mode, with file and line references into the v0.9.2 source. |
| [SwcBridge-ColdBoot-Handoff.md](SwcBridge-ColdBoot-Handoff.md) | The 20 Sep 2026 handoff that started the cold-boot investigation. |
| [report-09122026.md](report-09122026.md) | 12 Sep 2026 report: the original wheel → Poweramp fix and output modes. |
| [PLAN.md](PLAN.md) | The original plan for the bridge app. |
| [Lexus_Android_Headunit_info.md](Lexus_Android_Headunit_info.md) | Head unit hardware and firmware details (serial/IMEI redacted). |

The `.html` files in `infographics/` are the same pages as the PNGs; open them in a browser for crisp text.
