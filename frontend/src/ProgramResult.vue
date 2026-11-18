<script setup>
import { computed } from 'vue'

const props = defineProps({ item: { type: Object, required: true }, country: String, busy: Boolean })
defineEmits(['confirm'])
const language = computed(() => props.item.checks.find(check => check.evidence.id.endsWith(':ielts')))
const background = computed(() => props.item.checks.filter(check => !check.evidence.id.endsWith(':ielts')))
const languageStatus = { MATCH: '达到分数线', GAP: '低于分数线', UNKNOWN: '需要补充或逐项核对' }
</script>

<template>
  <article :id="`program-${item.program.id}`" class="panel result-card">
    <div class="program-meta">{{ country }} · {{ item.program.school }} · {{ item.program.intake }}</div>
    <h3>{{ item.program.name }}</h3>
    <p v-if="item.recommendation" class="ranking-reason">{{ item.recommendation.reason }}</p>

    <div class="score-grid">
      <section v-if="item.academics" class="score-box">
        <h4>学业成绩</h4><strong :class="item.academics.state.toLowerCase()">{{ item.academics.label }}</strong>
        <p>{{ item.academics.note }}</p>
        <a :href="item.academics.rule?.url || item.program.url" target="_blank" rel="noopener noreferrer">学业要求 ↗</a>
      </section>
      <section v-if="language" class="score-box">
        <h4>雅思学术类</h4><strong :class="language.status.toLowerCase()">{{ languageStatus[language.status] }}</strong>
        <p>{{ language.evidence.summary }}</p>
        <p v-if="language.status === 'UNKNOWN'" class="score-note">{{ language.note }}</p>
        <a :href="language.evidence.url" target="_blank" rel="noopener noreferrer">语言要求 ↗</a>
      </section>
    </div>
    <p v-for="check in background.filter(entry => entry.status === 'GAP')" :key="check.evidence.id" class="background-gap">{{ check.evidence.title }} · {{ check.note }}</p>

    <details v-if="item.guide" class="apply-details">
      <summary>查看申请步骤、时间和费用</summary>
      <div class="guide-dates">
        <div><h4>申请时间</h4><p>{{ item.guide.window }}</p><a :href="item.guide.datesUrl" target="_blank" rel="noopener noreferrer">官网时间 ↗</a></div>
        <div><h4>国际生学费</h4><p>{{ item.guide.tuition }}</p><a :href="item.guide.tuitionUrl" target="_blank" rel="noopener noreferrer">官网学费 ↗</a></div>
      </div>
      <ol class="application-steps">
        <li><strong>确认背景要求</strong><p v-for="check in background" :key="check.evidence.id">{{ check.evidence.summary }}</p></li>
        <li><strong>准备材料</strong><ul><li v-for="material in item.guide.materials" :key="material">{{ material }}</li></ul></li>
        <li><strong>按目标批次提交</strong><p>打开学校申请页，选定项目和入学批次，上传材料并按页面提示完成提交。</p><a :href="item.guide.applyUrl" target="_blank" rel="noopener noreferrer">学校申请页 ↗</a></li>
        <li><strong>跟踪申请状态</strong><p>检查申请系统及邮件，按学校通知补件或处理录取条件。</p></li>
      </ol>
      <button v-if="item.recommendation?.tier === 'FIT' || item.recommendation?.userConfirmed" type="button" class="button secondary compact" :disabled="busy" @click="$emit('confirm', item)">{{ item.recommendation.userConfirmed ? '撤销已核实标记' : '我已按官网核实其余条件' }}</button>
    </details>
    <p v-else class="legacy-note">此报告早于选校指南上线。重新生成对照可查看新版资料。</p>
  </article>
</template>
