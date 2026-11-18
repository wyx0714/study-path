<script setup>
import { computed, nextTick, onMounted, reactive, ref } from 'vue'
import AnswerCard from './AnswerCard.vue'
import ProgramResult from './ProgramResult.vue'

const countryName = { UK: '英国', SG: '新加坡', AU: '澳大利亚', US: '美国' }
const trackName = { ALL: '全部方向', COMPUTING: '计算机与转专业', AI_DATA: '数据科学与 AI' }
const gradeScales = { CN_PERCENT: '中国百分制均分', GPA4: '本科累计 GPA（4.0 制）', LAST60_GPA4: '最后 60 学分 GPA（4.0 制）', UK_HONOURS: '英国荣誉学位等级', MELBOURNE_WAM: '墨尔本大学口径 WAM' }
const tiers = {
  SAFETY_CANDIDATE: '相对保底', FIT: '匹配', REACH: '冲刺', VERIFY: '还差信息', GAP: '目前有差距', LEGACY: '早期报告',
}
const examples = ['我雅思 6.5，最低单项 6.0，够吗？', '本科背景有什么要求？', '需要准备哪些申请材料？']
const views = { match: '申请对照', history: '历史记录', sources: '规则资料库' }
const defaultProfile = {
  countries: ['UK', 'SG', 'AU', 'US'], track: 'ALL', year: 2027, major: 'UNKNOWN', instruction: 'UNKNOWN',
  math: null, programming: null, ieltsOverall: null, ieltsBand: null,
  confirmedProgramSnapshots: [],
  gradeScale: 'CN_PERCENT', grade: null,
}

const view = ref('match')
const profile = reactive({ ...defaultProfile, countries: [...defaultProfile.countries] })
const catalog = ref([])
const history = ref([])
const report = ref(null)
const tierFilter = ref('ALL')
const confirmError = ref('')
const model = ref({ configured: false, provider: '本地规则', framework: '' })
const plan = reactive({ loading: false, result: null, error: '' })
const resultsArea = ref(null)
const busy = ref(false)
const error = ref('')
const sourceError = ref('')
const assistant = reactive({ programId: '', compareId: '', question: '', busy: false, results: [] })

const visiblePrograms = computed(() => catalog.value.filter(program => profile.countries.includes(program.country)
  && (profile.track === 'ALL' || program.track === profile.track) && profile.year === program.year))
const referenceDate = computed(() => catalog.value.map(program => program.snapshot).sort().at(-1))
const groups = computed(() => Object.entries(tiers).map(([key, name]) => ({ key, name,
  items: (report.value?.results || []).filter(item => (item.recommendation?.tier || 'LEGACY') === key),
})).filter(group => group.key !== 'LEGACY' || group.items.length))
const shownGroups = computed(() => groups.value.filter(group => group.items.length
  && (tierFilter.value === 'ALL' || tierFilter.value === group.key)))
const shortlist = computed(() => groups.value.filter(group => ['SAFETY_CANDIDATE', 'FIT', 'REACH'].includes(group.key)))
const gradeMaximum = computed(() => ['GPA4', 'LAST60_GPA4'].includes(profile.gradeScale) ? 4 : 100)

async function api(path, body) {
  const response = await fetch(`/api${path}`, body === undefined ? {} : {
    method: 'POST', headers: { 'Content-Type': 'application/json' }, body: JSON.stringify(body),
  })
  if (!response.ok) {
    if (response.status === 400) throw new Error('请核对填写的条件或问题。')
    if (response.status === 404) throw new Error('找不到这条记录，请重新生成对照。')
    throw new Error('暂时无法取得结果，请重试。')
  }
  return response.json()
}

async function loadCatalog() {
  try {
    catalog.value = await api('/catalog')
  } catch (e) {
    sourceError.value = e.message
  }
}

async function loadModel() {
  try { model.value = await api('/model') } catch { /* assessment and local search remain available */ }
}

async function switchView(target) {
  view.value = target
  error.value = ''
  if (target !== 'history') return
  try {
    history.value = await api('/assessments')
  } catch (e) {
    error.value = e.message
  }
}

function fillExample() {
  Object.assign(profile, {
    ...defaultProfile, countries: ['UK'], major: 'QUANTITATIVE', instruction: 'OTHER',
    math: true, programming: true, ieltsOverall: 6.5, ieltsBand: 6, gradeScale: 'UK_HONOURS', grade: 2,
  })
  error.value = ''
}

function showReport(value) {
  report.value = value
  tierFilter.value = 'ALL'
  confirmError.value = ''
  plan.result = null
  plan.error = ''
  assistant.programId = value.results.find(item => item.program.id === 'ucl-cs')?.program.id || value.results[0]?.program.id || ''
  assistant.compareId = ''
  assistant.question = ''
  assistant.results = []
  nextTick(() => resultsArea.value?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

async function generatePlan() {
  if (plan.loading || !report.value) return
  const { id } = report.value
  plan.loading = true
  plan.error = ''
  plan.result = null
  try {
    const value = await api(`/assessments/${id}/plan`, {})
    if (report.value?.id === id) plan.result = value
  } catch (e) {
    if (report.value?.id === id) plan.error = e.message
  } finally {
    plan.loading = false
  }
}

function goToProgram(id) {
  tierFilter.value = 'ALL'
  nextTick(() => document.getElementById(`program-${id}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' }))
}

async function compare() {
  error.value = ''
  if (!profile.countries.length) {
    error.value = '请至少选择一个目标国家。'
    return
  }
  const overall = profile.ieltsOverall === '' ? null : profile.ieltsOverall
  const band = profile.ieltsBand === '' ? null : profile.ieltsBand
  if (overall !== null && band !== null && band > overall) {
    error.value = '雅思最低单项不能高于总分。'
    return
  }
  busy.value = true
  try {
    const backgroundMatches = report.value && ['major', 'instruction', 'math', 'programming', 'gradeScale', 'grade'].every(key => profile[key] === report.value.profile[key])
    const confirmedProgramSnapshots = backgroundMatches ? report.value.profile.confirmedProgramSnapshots : []
    showReport(await api('/assessments', { ...profile, grade: profile.grade === '' ? null : profile.grade, ieltsOverall: overall, ieltsBand: band, confirmedProgramSnapshots }))
  } catch (e) {
    error.value = e.message
  } finally {
    busy.value = false
  }
}

async function toggleProgramConfirmation(item) {
  if (busy.value) return
  const confirmed = new Set(report.value.profile.confirmedProgramSnapshots || [])
  const key = `${item.program.id}@${item.program.year}/${item.program.snapshot}`
  if (confirmed.has(key)) confirmed.delete(key)
  else confirmed.add(key)
  confirmError.value = ''
  busy.value = true
  try {
    showReport(await api('/assessments', { ...report.value.profile, confirmedProgramSnapshots: [...confirmed] }))
  } catch (e) {
    confirmError.value = e.message
  } finally {
    busy.value = false
  }
}

async function restore(id) {
  try {
    const previous = await api(`/assessments/${id}`)
    Object.assign(profile, { ...defaultProfile, ...previous.profile, gradeScale: previous.profile.gradeScale || 'CN_PERCENT', countries: [...previous.profile.countries] })
    view.value = 'match'
    showReport(previous)
  } catch (e) {
    error.value = e.message
  }
}

function changeQueryProgram() {
  if (assistant.compareId === assistant.programId) assistant.compareId = ''
  assistant.results = []
}

async function ask() {
  if (assistant.busy || !assistant.programId) return
  const question = assistant.question.trim()
  if (!question) return
  const { id } = report.value
  const ids = [assistant.programId, assistant.compareId].filter(Boolean)
  const programs = ids.map(programId => report.value.results.find(item => item.program.id === programId).program)
  assistant.busy = true
  assistant.results = []
  try {
    const responses = await Promise.allSettled(programs.map(program =>
      api(`/assessments/${id}/programs/${program.id}/ask`, { question })))
    if (id === report.value?.id) {
      assistant.results = responses.map((response, index) => response.status === 'fulfilled'
        ? { program: programs[index], answer: response.value }
        : { program: programs[index], error: response.reason.message })
    }
  } finally {
    assistant.busy = false
  }
}

onMounted(() => { loadCatalog(); loadModel() })
</script>

<template>
  <div class="workspace">
    <aside class="sidebar">
      <div class="brand"><span class="brand-mark" aria-hidden="true">研</span><div><strong>研途</strong><small>StudyPath</small></div></div>
      <nav aria-label="页面导航">
        <button v-for="(label, key) in views" :key="key" :class="{ active: view === key }" :aria-current="view === key ? 'page' : undefined" @click="switchView(key)">{{ label }}<span v-if="view === key" aria-hidden="true">›</span></button>
      </nav>
    </aside>

    <main class="main">
      <div v-if="view === 'match'">
        <header class="hero">
          <div class="hero-title">
            <h1>留学申请对照</h1>
            <p>选择目的地和方向，用同一份背景对照不同硕士项目的官网要求。</p>
          </div>
          <button class="button secondary" @click="fillExample">填入示例</button>
        </header>

        <div class="input-layout">
          <form class="panel profile" @submit.prevent="compare">
            <div class="panel-heading"><h2>申请背景</h2><p>未确定的条件可留空。</p></div>
            <fieldset>
              <legend>目标国家</legend>
              <div class="options">
                <label v-for="(name, code) in countryName" :key="code" class="option" :class="{ selected: profile.countries.includes(code) }">
                  <input v-model="profile.countries" type="checkbox" :value="code">{{ name }}
                </label>
              </div>
            </fieldset>
            <div class="fields">
              <label>申请方向<select v-model="profile.track"><option v-for="(name, code) in trackName" :key="code" :value="code">{{ name }}</option></select></label>
              <label>入学年份<select v-model.number="profile.year"><option :value="2027">2027</option><option :value="2028">2028</option></select></label>
              <label>本科专业<select v-model="profile.major"><option value="UNKNOWN">未确定</option><option value="COMPUTING">计算机、软件工程及相关专业</option><option value="QUANTITATIVE">数学、统计等非计算机定量专业</option><option value="OTHER">其他非计算机专业</option></select></label>
              <label>本科主要授课语言<select v-model="profile.instruction"><option value="UNKNOWN">未确定</option><option value="ENGLISH">英语</option><option value="OTHER">其他语言</option></select></label>
              <label>GPA 或均分口径<select v-model="profile.gradeScale" @change="profile.grade = null"><option v-for="(label, value) in gradeScales" :key="value" :value="value">{{ label }}</option></select></label>
              <label v-if="profile.gradeScale === 'UK_HONOURS'">学位等级<select v-model="profile.grade"><option :value="null">未填写</option><option :value="3">一等荣誉</option><option :value="2">二等一（2:1）</option><option :value="1">二等二（2:2）</option><option :value="0">三等</option></select></label>
              <label v-else>GPA 或均分<input v-model.number="profile.grade" type="number" min="0" :max="gradeMaximum" step="0.01" :placeholder="gradeMaximum === 4 ? '例如 3.2' : '例如 85'"></label>
              <label>雅思学术类总分<input v-model.number="profile.ieltsOverall" type="number" min="0" max="9" step="0.5" placeholder="例如 6.5"></label>
              <label>雅思最低单项<input v-model.number="profile.ieltsBand" type="number" min="0" max="9" step="0.5" placeholder="例如 6.0"></label>
            </div>
            <details class="optional-background"><summary>补充课程背景（可选）</summary><div class="fields">
              <label>数学学习证明<select v-model="profile.math"><option :value="null">未填写</option><option :value="true">能提供</option><option :value="false">暂无</option></select></label>
              <label>高级语言编程经验<select v-model="profile.programming"><option :value="null">未填写</option><option :value="true">有</option><option :value="false">暂无</option></select></label>
            </div></details>
            <div class="form-footer"><button class="button primary" :disabled="busy" type="submit">{{ busy ? '正在对照…' : '生成申请对照' }} <span aria-hidden="true">→</span></button><p v-if="error" role="alert" class="error">{{ error }}</p></div>
          </form>

          <section class="panel scope" aria-label="覆盖范围">
            <div class="panel-heading"><h2>筛选范围内的项目<span v-if="catalog.length" class="program-count">{{ visiblePrograms.length }}</span></h2><p>根据当前国家、方向和年份筛选。</p></div>
            <p v-if="sourceError" class="error">{{ sourceError }}</p>
            <p v-else-if="!catalog.length" class="muted">正在载入项目…</p>
            <p v-else-if="!visiblePrograms.length" class="muted">所选条件暂无项目资料。</p>
            <ul v-else class="catalog-short">
              <li v-for="program in visiblePrograms" :key="program.id"><span>{{ countryName[program.country] }} · {{ program.kind }}</span><strong>{{ program.name }}</strong><small>{{ program.school }}</small></li>
            </ul>
            <div v-if="referenceDate" class="scope-footer">官网资料核对日期 {{ referenceDate }}</div>
          </section>
        </div>

        <section v-if="report" ref="resultsArea" class="results">
          <div class="section-heading"><h2>选校建议</h2><p>{{ report.profile.year }} 入学 · {{ report.results.length }} 个项目 · 按当前资料逐项核对</p></div>
          <div v-if="!report.results.length" class="panel empty">当前筛选条件暂无资料。可切换至已有资料的年份或方向。</div>
          <template v-else>
            <div v-if="report.results.some(item => item.guide)" class="shortlist-grid">
              <section v-for="group in shortlist" :key="group.key" class="panel shortlist-card" :class="group.key.toLowerCase()">
                <h3>{{ group.name }} <span>{{ group.items.length }}</span></h3>
                <p v-if="!group.items.length">当前没有该层项目。</p>
                <ul v-else><li v-for="item in group.items.slice(0, 4)" :key="item.program.id"><button type="button" @click="goToProgram(item.program.id)"><strong>{{ item.program.school }}</strong><small>{{ item.program.name }}</small></button></li></ul>
                <button v-if="group.items.length > 4" type="button" class="shortlist-more" @click="tierFilter = group.key">查看全部 {{ group.items.length }} 个</button>
              </section>
            </div>
            <section v-if="report.results.some(item => item.guide)" class="panel ai-plan">
              <div class="plan-heading"><div><h3>申请准备计划</h3><p>{{ model.configured ? `${model.framework} + ${model.provider} 已配置` : '本地规则' }} · 根据这次分层整理下一步。</p></div><button type="button" class="button primary" :disabled="plan.loading" @click="generatePlan">{{ plan.loading ? '正在生成…' : model.configured ? `用 ${model.provider} 生成计划` : '查看准备建议' }}</button></div>
              <p v-if="plan.error" class="error" role="alert">{{ plan.error }}</p>
              <div v-if="plan.result" class="plan-output" aria-live="polite"><span class="mode">{{ plan.result.generated ? `${model.framework} + ${plan.result.provider} 已生成` : '本地准备建议' }}</span><p>{{ plan.result.advice }}</p></div>
            </section>
            <div class="tier-tabs" aria-label="按选校建议筛选">
              <button type="button" class="tier-tab" :class="{ 'current-tier': tierFilter === 'ALL' }" :aria-pressed="tierFilter === 'ALL'" @click="tierFilter = 'ALL'">全部 <span>{{ report.results.length }}</span></button>
              <button v-for="group in groups" :key="group.key" type="button" class="tier-tab" :class="{ 'current-tier': tierFilter === group.key }" :aria-pressed="tierFilter === group.key" @click="tierFilter = group.key">{{ group.name }} <span>{{ group.items.length }}</span></button>
            </div>
            <p class="tier-note">分层依据已公开的入学条件和你填的成绩。相对保底须核实其他要求并留有语言余量，最终由学校审核。</p>
            <p v-if="confirmError" class="error" role="alert">{{ confirmError }}</p>
            <p v-if="!shownGroups.length" class="panel empty">这次对照暂无该层的项目。</p>
            <section v-for="group in shownGroups" :key="group.key" class="tier-group">
              <h3>{{ group.name }} <span>{{ group.items.length }}</span></h3>
              <div class="result-grid">
                <ProgramResult v-for="item in group.items" :key="item.program.id" :item="item" :country="countryName[item.program.country]" :busy="busy" @confirm="toggleProgramConfirmation" />
              </div>
            </section>
          </template>

          <article v-if="report.results.length" class="panel assistant">
            <div class="panel-heading"><h2>查官网依据</h2><p>可选择两个项目，用同一个问题对照结论。请勿在问题中填写姓名、证件号等个人资料。</p></div>
            <form class="ask-form" @submit.prevent="ask()">
              <div class="query-programs">
                <label>查询项目<select v-model="assistant.programId" :disabled="assistant.busy" @change="changeQueryProgram"><option v-for="item in report.results" :key="item.program.id" :value="item.program.id">{{ item.program.school }} · {{ item.program.name }}</option></select></label>
                <label v-if="report.results.length > 1">对照项目（可选）<select v-model="assistant.compareId" :disabled="assistant.busy" @change="changeQueryProgram"><option value="">选择第二个项目</option><option v-for="item in report.results.filter(result => result.program.id !== assistant.programId)" :key="item.program.id" :value="item.program.id">{{ item.program.school }} · {{ item.program.name }}</option></select></label>
              </div>
              <div class="query-question"><label>问题<input v-model.trim="assistant.question" :disabled="assistant.busy" placeholder="例如：雅思最低要求是多少？" maxlength="300" required></label><button class="button primary" :disabled="assistant.busy" type="submit">{{ assistant.busy ? '检索中…' : '查依据' }}</button></div>
              <div class="sample-questions"><button v-for="example in examples" :key="example" type="button" class="question-chip" :disabled="assistant.busy" @click="assistant.question = example">{{ example }}</button></div>
            </form>
            <div v-if="assistant.results.length" class="answer-grid" :class="{ paired: assistant.results.length > 1 }" aria-live="polite">
              <AnswerCard v-for="item in assistant.results" :key="item.program.id" :item="item" :provider="model.framework ? `${model.framework} + ${model.provider}` : model.provider" />
            </div>
          </article>
        </section>
      </div>

      <div v-else-if="view === 'history'">
        <header class="hero"><div class="hero-title"><h1>历史记录</h1><p>查看之前的申请对照及当时使用的规则。</p></div></header>
        <p v-if="error" class="error" role="alert">{{ error }}</p>
        <div v-else class="panel list-panel">
          <p v-if="!history.length" class="muted">生成一次申请对照后，记录会显示在这里。</p>
          <div v-for="item in history" :key="item.id" class="list-row"><div><strong>{{ item.countries.map(code => countryName[code]).join('、') }} · {{ trackName[item.track] }}</strong><small>{{ new Date(item.createdAt).toLocaleString('zh-CN') }} · {{ item.year }} 入学 · {{ item.count }} 个项目</small></div><button class="button secondary" @click="restore(item.id)">查看对照</button></div>
        </div>
      </div>

      <div v-else>
        <header class="hero"><div class="hero-title"><h1>规则资料库</h1><p>查看已收录的项目、资料版本与学校出处。</p></div></header>
        <p v-if="sourceError" class="error">{{ sourceError }}</p>
        <div v-else class="result-grid">
          <article v-for="program in catalog" :key="program.id" class="panel result-card"><div class="program-meta">{{ countryName[program.country] }} · {{ program.school }} · {{ program.intake }}</div><h3>{{ program.name }}</h3><p class="muted">资料版本 {{ program.snapshot }}</p><a :href="program.url" target="_blank" rel="noopener noreferrer">查看学校官网 ↗</a><div v-for="rule in program.rules" :key="rule.id" class="check"><strong>{{ rule.title }}</strong><p>{{ rule.summary }}</p><a :href="rule.url || program.url" target="_blank" rel="noopener noreferrer">{{ rule.section }} ↗</a></div></article>
        </div>
      </div>
    </main>
  </div>
</template>
