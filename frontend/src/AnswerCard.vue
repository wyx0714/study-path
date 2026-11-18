<script setup>
import { ref } from 'vue'

const props = defineProps({ item: { type: Object, required: true }, provider: { type: String, default: 'AI' } })
const modes = {
  RETRIEVAL: '规则解答 · 本地检索',
  NO_EVIDENCE: '证据不足',
  MODEL_UNAVAILABLE: '已回退至本地规则解答',
}
const copyStatus = ref('')

async function copyAnswer() {
  const { program, answer } = props.item
  const text = [
    `${program.name} · ${program.intake} · 资料 ${program.snapshot}`,
    answer.conclusion,
    answer.explanation,
    ...answer.sources.map((source, index) => `依据 ${index + 1} ${source.title}\n${source.section}\n${source.url}`),
    ...answer.toVerify.map(note => `下一步 ${note}`),
  ].join('\n\n')
  try {
    await navigator.clipboard.writeText(text)
    copyStatus.value = '已复制'
  } catch {
    copyStatus.value = '复制失败，可手动选中回答'
  }
}
</script>

<template>
  <article class="answer-card">
    <div class="answer-header"><div><p class="program-meta">{{ item.program.school }} · {{ item.program.intake }}</p><h3>{{ item.program.name }}</h3></div><button v-if="item.answer" type="button" class="button secondary compact" @click="copyAnswer">复制回答</button></div>
    <p v-if="copyStatus" class="copy-status" role="status">{{ copyStatus }}</p>
    <p v-if="item.error" class="error" role="alert">{{ item.error }}</p>
    <template v-else>
      <span class="mode" :class="{ 'mode-review': item.answer.mode === 'NO_EVIDENCE' }">{{ item.answer.mode === 'RAG' ? `${provider} 解读 · 引用已校验` : modes[item.answer.mode] }}</span>
      <section class="answer-group">
        <h4>结论</h4>
        <strong>{{ item.answer.conclusion }}</strong>
        <p>{{ item.answer.explanation }}</p>
      </section>
      <section v-if="item.answer.sources.length" class="answer-group">
        <h4>依据</h4>
        <div v-for="source in item.answer.sources" :key="source.id" class="answer-source">
          <strong>{{ source.title }}</strong><p>{{ source.summary }}</p><a :href="source.url" target="_blank" rel="noopener noreferrer">{{ source.section }} ↗</a><small>资料版本 {{ source.snapshot }}</small>
        </div>
      </section>
      <section v-if="item.answer.toVerify.length" class="answer-group verify">
        <h4>待确认与下一步</h4>
        <p v-for="note in item.answer.toVerify" :key="note">{{ note }}</p>
      </section>
    </template>
  </article>
</template>
