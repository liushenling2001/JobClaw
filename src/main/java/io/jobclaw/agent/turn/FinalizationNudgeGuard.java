package io.jobclaw.agent.turn;

import io.jobclaw.agent.completion.CompletionGateResult;
import java.util.regex.Pattern;

/** Shares one heuristic nudge per user turn; registered completion checks take precedence. */
public final class FinalizationNudgeGuard {
    public static final int MAX_CANDIDATE_CHARS = 240;
    private static final String ACTION_LEAD =
            "(?:让我(?:先)?|我(?:先|再|来|会|要|将|准备|打算)"
                    + "|我(?:现在|马上|立刻|接下来|下一步|接着|继续)(?:会|要|将)?(?:先|再)?"
                    + "|(?:接下来|下一步|现在|随后|接着|然后)[，,\\h]*(?:我(?:会|要|将)?)?(?:先|再)?|继续)";
    private static final String ACTION =
            "(?:检查|查看|读取|搜索|查找|列出|整理|分类|移动|重命名|创建|生成|保存|写入|修改|更新|删除"
                    + "|运行|执行|测试|提取|处理|打开|分析|扫描|检索|核对|汇总|导出|统计|校验|对比|遍历|转换|解压|合并|复制)";
    private static final Pattern PROMISE = Pattern.compile(
            "(?:好的[，,。！!\\s]*)?"
                    + ACTION_LEAD + "(?:先|再|继续|接着)?(?:来|开始)?" + ACTION + "(?!了)"
                    + "[^。！？!?；;\\r\\n]*[。！!…]*");
    private static final Pattern NON_ACTION = Pattern.compile(
            "[?？]|[`~<>\"“”‘’]|(?:如果|假如|若|等你|等您|等待|需要你|需要您|请你|请您|是否|确认|授权|无法|不能"
                    + "|没有权限|不可用|不执行|已完成|已经|完成了|已结束|结果如下|说法|这句话|例如|示例|教程|建议"
                    + "|方法|步骤|操作说明|即可|只是计划)|(?:吗|呢|吧)[。！!…]*$");
    private boolean issued;

    public boolean hasIssued() {
        return issued;
    }

    public String continuationPrompt(CompletionGateResult completion, String artifactPrompt,
                                     String lastSegment, boolean waitingForUser, boolean activeExecution) {
        if (completion.registered() || issued || waitingForUser || activeExecution) {
            return "";
        }
        boolean artifact = artifactPrompt != null && !artifactPrompt.isBlank();
        boolean promise = matches(lastSegment);
        if (!artifact && !promise) {
            return "";
        }
        issued = true;
        StringBuilder prompt = new StringBuilder("JOBCLAW_FINALIZATION_NUDGE\n");
        if (artifact) {
            prompt.append(artifactPrompt).append("\n\n");
        }
        if (promise) {
            prompt.append("JOBCLAW_PROMISE_CONTINUATION\n"
                + "Your last reply announced an action, but no tool call followed it. "
                + "Continue only the current user request. If the announced action is required and authorized, "
                + "execute it using the available tools instead of repeating the plan or previous answer. "
                + "Do not redo work already completed. If user information or permission is needed, "
                + "ask the concrete question using user_input when available; otherwise explain the blocker. "
                + "If no further action is required, briefly clarify that.");
        }
        return prompt.toString();
    }

    static boolean matches(String content) {
        if (content == null || content.length() > MAX_CANDIDATE_CHARS) {
            return false;
        }
        String candidate = content.strip();
        return !NON_ACTION.matcher(candidate).find() && PROMISE.matcher(candidate).matches();
    }
}
