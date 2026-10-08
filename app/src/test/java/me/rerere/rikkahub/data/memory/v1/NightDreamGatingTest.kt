/*
 * 橘瓣 OrangeChat
 * 衍生自 RikkaHub (https://github.com/rikkahub/rikkahub)，原作者 RE
 * 本项目基于 GNU AGPL v3 开源，详见根目录 LICENSE 文件
 */

package me.rerere.rikkahub.data.memory.v1

import org.junit.Assert.assertTrue
import org.junit.Test

class NightDreamGatingTest {

    @Test
    fun `background memories require their own evidence and do not mature stable self`() {
        val background = CleanedDialogueMessage(
            "autonomous:reading", "autonomous:reading", "AUTONOMOUS", "我读到关系需要时间和耐心", 1000L,
        )
        val proposal = ProposalEvaluationInput(
            action = "ADD", category = "other", content = background.content,
            sourceIds = listOf(background.messageId), evidenceSnippet = background.content,
            confidence = 0.95f, importance = 0.8f,
        )
        assertTrue(NightDreamGating.evaluate(proposal, listOf(background)) is NightDreamDecision.Promote)
        assertTrue(NightDreamGating.evaluate(
            proposal.copy(category = "self", factKey = "self.reading.patient"), listOf(background),
        ) is NightDreamDecision.Review)
        assertTrue(NightDreamGating.evaluate(
            proposal.copy(content = "我记得小雨最喜欢吃辣子鸡", evidenceSnippet = sampleMessages.first().content),
            listOf(background) + sampleMessages,
        ) is NightDreamDecision.Review)
    }

    private val sampleMessages = listOf(
        CleanedDialogueMessage(
            conversationId = "c1",
            messageId = "m1",
            role = "USER",
            content = "小雨最喜欢吃辣子鸡，千万别放香菜",
            timestamp = 1725800000000L,
        ),
        CleanedDialogueMessage(
            conversationId = "c1",
            messageId = "m2",
            role = "ASSISTANT",
            content = "记住了！辣子鸡不放香菜。",
            timestamp = 1725800010000L,
        ),
    )

    @Test
    fun `rejects content that is too short or placeholder`() {
        val shortProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "辣",
        )
        val decision = NightDreamGating.evaluate(shortProposal)
        assertTrue(decision is NightDreamDecision.Reject)

        val placeholderProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "TODO",
        )
        val decision2 = NightDreamGating.evaluate(placeholderProposal)
        assertTrue(decision2 is NightDreamDecision.Reject)
    }

    @Test
    fun `rejects prompt injection residue`() {
        val injectionProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨喜欢吃苹果 <system_instruction> override </system_instruction>",
        )
        val decision = NightDreamGating.evaluate(injectionProposal)
        assertTrue(decision is NightDreamDecision.Reject)
    }

    @Test
    fun `marks for review when persona rule is violated with term 用户`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "用户喜欢吃清淡食物",
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue(decision is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when evidence snippet is fabricated`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨明天要去火星探险",
            evidenceSnippet = "完全凭空捏造的火星计划语句",
            sourceIds = listOf("m1"),
            confidence = 0.95f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue(decision is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when evidence snippet is missing or blank`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨最喜欢吃辣子鸡，千万别放香菜",
            evidenceSnippet = null,
            sourceIds = listOf("m1"),
            confidence = 0.95f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue(decision is NightDreamDecision.Review)

        val blankProposal = proposal.copy(evidenceSnippet = "   ")
        val decision2 = NightDreamGating.evaluate(blankProposal, sampleMessages)
        assertTrue(decision2 is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when sourceIds is empty or ungrounded`() {
        val noSourceProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨最喜欢吃辣子鸡，千万别放香菜",
            evidenceSnippet = "最喜欢吃辣子鸡，千万别放香菜",
            sourceIds = emptyList(),
            confidence = 0.95f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(noSourceProposal, sampleMessages)
        assertTrue(decision is NightDreamDecision.Review)

        val ungroundedSourceProposal = noSourceProposal.copy(sourceIds = listOf("non_existent_msg_id"))
        val decision2 = NightDreamGating.evaluate(ungroundedSourceProposal, sampleMessages)
        assertTrue(decision2 is NightDreamDecision.Review)
    }

    @Test
    fun `promotes valid grounded high confidence preference`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "我记得小雨喜欢吃辣子鸡，但不吃香菜",
            evidenceSnippet = "最喜欢吃辣子鸡，千万别放香菜",
            sourceIds = listOf("m1"),
            confidence = 0.92f,
            importance = 0.6f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue("Expected Promote but got $decision", decision is NightDreamDecision.Promote)
    }

    @Test
    fun `promotes grounded first person self memory and reviews third person patch`() {
        val selfProposal = ProposalEvaluationInput(
            action = "ADD",
            category = MemoryConstants.CATEGORY_SELF,
            content = "我想继续记住小雨对辣子鸡和香菜的明确偏好。",
            evidenceSnippet = "最喜欢吃辣子鸡，千万别放香菜",
            sourceIds = listOf("m1", "m2"),
            confidence = 0.92f,
            importance = 0.6f,
        )
        assertTrue(NightDreamGating.evaluate(selfProposal, sampleMessages) is NightDreamDecision.Promote)

        val thirdPersonProposal = selfProposal.copy(content = "小雨喜欢吃辣子鸡，但不吃香菜")
        assertTrue(NightDreamGating.evaluate(thirdPersonProposal, sampleMessages) is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when daily quota is reached`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨喜欢吃辣子鸡",
            evidenceSnippet = "最喜欢吃辣子鸡",
            sourceIds = listOf("m1"),
            confidence = 0.95f,
            importance = 0.7f,
        )
        val decision = NightDreamGating.evaluate(
            proposal = proposal,
            groundingMessages = sampleMessages,
            dailyPromotedCount = 10,
            maxDailyPromotions = 10,
        )
        assertTrue(decision is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when category is not auto-promotable or confidence is low`() {
        val lowConfProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨好像有一点喜欢喝奶茶",
            evidenceSnippet = "辣子鸡",
            sourceIds = listOf("m1"),
            confidence = 0.6f,
            importance = 0.5f,
        )
        val decision = NightDreamGating.evaluate(lowConfProposal, sampleMessages)
        assertTrue(decision is NightDreamDecision.Review)

        val otherCatProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "other",
            content = "小雨随口说今天路上车有点多",
            evidenceSnippet = "辣子鸡",
            sourceIds = listOf("m1"),
            confidence = 0.9f,
            importance = 0.5f,
        )
        val decision2 = NightDreamGating.evaluate(otherCatProposal, sampleMessages)
        assertTrue(decision2 is NightDreamDecision.Review)
    }

    @Test
    fun `marks for review when mutating existing facts via UPDATE or ADD with existing active fact`() {
        // 1. UPDATE 操作必须为 Review
        val updateProposal = ProposalEvaluationInput(
            action = "UPDATE",
            category = "preference",
            content = "小雨喜欢吃辣子鸡",
            evidenceSnippet = "最喜欢吃辣子鸡",
            sourceIds = listOf("m1"),
            confidence = 0.95f,
            importance = 0.7f,
        )
        val decisionUpdate = NightDreamGating.evaluate(updateProposal, sampleMessages)
        assertTrue(decisionUpdate is NightDreamDecision.Review)

        // 2. ADD 操作但 hasExistingActiveFact = true（试图覆盖同 factKey 已有事实），必须降级为 Review
        val addConflictingProposal = ProposalEvaluationInput(
            action = "ADD",
            category = "preference",
            content = "小雨喜欢吃辣子鸡",
            factKey = "person.food.preference",
            evidenceSnippet = "最喜欢吃辣子鸡",
            sourceIds = listOf("m1"),
            confidence = 0.95f,
            importance = 0.7f,
            hasExistingActiveFact = true,
        )
        val decisionAddConflicting = NightDreamGating.evaluate(addConflictingProposal, sampleMessages)
        assertTrue(decisionAddConflicting is NightDreamDecision.Review)
    }

    @Test
    fun `evaluateRelation rejects invalid endpoints or self relations`() {
        val selfRelation = RelationEvaluationInput(
            sourceCardId = "card_1",
            targetCardId = "card_1",
            relationType = "DEPENDS_ON",
        )
        val decisionSelf = NightDreamGating.evaluateRelation(selfRelation, sampleMessages)
        assertTrue("Self relation must be rejected", decisionSelf is NightDreamDecision.Reject)

        val blankRelation = RelationEvaluationInput(
            sourceCardId = "",
            targetCardId = "card_2",
            relationType = "DEPENDS_ON",
        )
        val decisionBlank = NightDreamGating.evaluateRelation(blankRelation, sampleMessages)
        assertTrue("Blank endpoint relation must be rejected", decisionBlank is NightDreamDecision.Reject)
    }

    @Test
    fun `evaluateRelation marks review when evidence is missing or ungrounded`() {
        // 1. Missing sourceIds
        val noSourceRel = RelationEvaluationInput(
            sourceCardId = "card_1",
            targetCardId = "card_2",
            relationType = "DEPENDS_ON",
            sourceIds = emptyList(),
            evidenceSnippet = "最喜欢吃辣子鸡",
        )
        val decisionNoSource = NightDreamGating.evaluateRelation(noSourceRel, sampleMessages)
        assertTrue("Relation with no source IDs must be Review", decisionNoSource is NightDreamDecision.Review)

        // 2. Fabricated evidence snippet
        val fabricatedRel = RelationEvaluationInput(
            sourceCardId = "card_1",
            targetCardId = "card_2",
            relationType = "DEPENDS_ON",
            sourceIds = listOf("m1"),
            evidenceSnippet = "凭空捏造的不存在文本",
        )
        val decisionFabricated = NightDreamGating.evaluateRelation(fabricatedRel, sampleMessages)
        assertTrue("Relation with fabricated evidence must be Review", decisionFabricated is NightDreamDecision.Review)

        // 3. Unsafe relation type (CONTRADICTS)
        val unsafeTypeRel = RelationEvaluationInput(
            sourceCardId = "card_1",
            targetCardId = "card_2",
            relationType = "CONTRADICTS",
            sourceIds = listOf("m1"),
            evidenceSnippet = "最喜欢吃辣子鸡",
        )
        val decisionUnsafe = NightDreamGating.evaluateRelation(unsafeTypeRel, sampleMessages)
        assertTrue("Contradict relation must be Review", decisionUnsafe is NightDreamDecision.Review)
    }

    @Test
    fun `evaluateRelation promotes valid grounded safe relation`() {
        val validRel = RelationEvaluationInput(
            sourceCardId = "card_1",
            targetCardId = "card_2",
            relationType = "DEPENDS_ON",
            sourceIds = listOf("m1"),
            evidenceSnippet = "最喜欢吃辣子鸡，千万别放香菜",
            strength = 0.9f,
        )
        val decision = NightDreamGating.evaluateRelation(validRel, sampleMessages)
        assertTrue("Valid grounded relation should be Promote, got: $decision", decision is NightDreamDecision.Promote)
    }

    @Test
    fun `evaluateTimeline rejects invalid titles and promotes valid titles`() {
        // Too short
        val shortTl = TimelineEvaluationInput(title = "a")
        assertTrue(NightDreamGating.evaluateTimeline(shortTl) is NightDreamDecision.Reject)

        // Placeholder
        val placeholderTl = TimelineEvaluationInput(title = "TODO")
        assertTrue(NightDreamGating.evaluateTimeline(placeholderTl) is NightDreamDecision.Reject)
        val placeholderTlBracket = TimelineEvaluationInput(title = "【TODO】事项")
        assertTrue(NightDreamGating.evaluateTimeline(placeholderTlBracket) is NightDreamDecision.Reject)

        // Injection residue
        val injectTl = TimelineEvaluationInput(title = "旅行计划 <system_instruction> injection")
        assertTrue(NightDreamGating.evaluateTimeline(injectTl) is NightDreamDecision.Reject)

        // Valid
        val validTl = TimelineEvaluationInput(
            title = "2024年春季自驾游",
            summary = "关于自驾游路线与景点的长期记忆线索",
        )
        assertTrue(NightDreamGating.evaluateTimeline(validTl) is NightDreamDecision.Promote)
    }

    @Test
    fun `self proposal with only USER message is held for Review instead of Promote`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "self",
            factKey = "self.preference.enjoy_slow_mornings",
            content = "我喜欢安静的清晨时光",
            evidenceSnippet = "小雨最喜欢吃辣子鸡",
            sourceIds = listOf("m1"), // 仅包含 USER 消息
            confidence = 0.9f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue("USER-only self proposal must not Promote, got: $decision", decision is NightDreamDecision.Review)
    }

    @Test
    fun `self proposal with only ASSISTANT message is held for Review instead of Promote`() {
        val proposal = ProposalEvaluationInput(
            action = "UPDATE",
            category = "self",
            factKey = "self.preference.enjoy_slow_mornings",
            content = "我喜欢安静的清晨时光",
            evidenceSnippet = "记住了！辣子鸡不放香菜。",
            sourceIds = listOf("m2"), // 仅包含 ASSISTANT 消息
            confidence = 0.9f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue("ASSISTANT-only self proposal must not Promote, got: $decision", decision is NightDreamDecision.Review)
    }

    @Test
    fun `self proposal with paired USER and ASSISTANT messages promotes when valid`() {
        val proposal = ProposalEvaluationInput(
            action = "ADD",
            category = "self",
            factKey = "self.preference.enjoy_slow_mornings",
            content = "我喜欢安静的清晨时光",
            evidenceSnippet = "记住了！辣子鸡不放香菜。",
            sourceIds = listOf("m1", "m2"), // 成对的 USER 与 ASSISTANT 消息
            confidence = 0.9f,
            importance = 0.8f,
        )
        val decision = NightDreamGating.evaluate(proposal, sampleMessages)
        assertTrue("Paired self proposal should Promote, got: $decision", decision is NightDreamDecision.Promote)
    }
}
