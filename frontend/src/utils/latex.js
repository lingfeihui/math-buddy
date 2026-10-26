/**
 * KaTeX 常见命令词表，按长度降序排列。
 * 用途：AI 偶尔会把命令和后面的变量粘在一起（如 \Rightarrowp、\inA），
 * KaTeX 会把整串当成一个不存在的命令而报错，显示成红色源码。
 */
const LATEX_COMMANDS = [
  'Leftrightarrow', 'Longrightarrow', 'Longleftarrow', 'longrightarrow', 'longleftarrow',
  'leftrightarrow', 'Rightarrow', 'Leftarrow', 'rightarrow', 'leftarrow', 'nsubseteq', 'nsupseteq',
  'varnothing', 'displaystyle', 'scriptstyle', 'operatorname', 'overrightarrow', 'overleftarrow',
  'nparallel', 'triangle', 'emptyset', 'subseteq', 'supseteq', 'overset', 'underset',
  'boldsymbol', 'therefore', 'because', 'mathbb', 'mathrm', 'mathcal', 'mathbf', 'mathsf',
  'mathtt', 'mathit', 'textbf', 'textit', 'textrm', 'textsf', 'texttt', 'textstyle',
  'mathop', 'mathbin', 'mathrel', 'mathord', 'mathstrut', 'mathring', 'mathnormal',
  'overline', 'underline', 'stackrel', 'cancel', 'boxed', 'binom', 'dbinom', 'tbinom',
  'dfrac', 'tfrac', 'cfrac', 'frac', 'sqrt', 'partial', 'nabla', 'infty', 'notin', 'nmid',
  'limits', 'nolimits', 'middle', 'ldots', 'cdots', 'dots', 'left', 'right',
  'begin', 'end', 'sum', 'prod', 'int', 'iint', 'iiint', 'oint', 'lim', 'sup', 'inf',
  'max', 'min', 'log', 'ln', 'lg', 'sin', 'cos', 'tan', 'cot', 'sec', 'csc',
  'arcsin', 'arccos', 'arctan', 'sinh', 'cosh', 'tanh', 'alpha', 'beta', 'gamma',
  'delta', 'epsilon', 'varepsilon', 'zeta', 'eta', 'theta', 'vartheta', 'iota', 'kappa',
  'lambda', 'mu', 'nu', 'xi', 'pi', 'rho', 'varrho', 'sigma', 'varsigma', 'tau',
  'upsilon', 'phi', 'varphi', 'chi', 'psi', 'omega', 'Gamma', 'Delta', 'Theta', 'Lambda',
  'Xi', 'Pi', 'Sigma', 'Upsilon', 'Phi', 'Psi', 'Omega', 'cdot', 'times', 'div', 'pm',
  'mp', 'leq', 'leqslant', 'geq', 'geqslant', 'neq', 'approx', 'equiv', 'simeq', 'cong',
  'propto', 'subset', 'supset', 'setminus', 'bigcup', 'bigcap', 'bigoplus', 'bigotimes',
  'parallel', 'perp', 'circ', 'prime', 'quad', 'qquad', 'langle', 'rangle', 'implies',
  'impliedby', 'iff', 'degree', 'bullet', 'oplus', 'otimes', 'odot', 'star', 'ast',
  'checkmark', 'square', 'bigcirc', 'text', 'over', 'bar', 'hat', 'tilde', 'vec', 'dot',
  'ddot', 'top', 'to', 'in', 'ni', 'ne', 'le', 'ge', 'll', 'gg', 'not', 'neg',
  'mid', 'nmid', 'vert', 'Vert', 'lvert', 'rvert', 'lVert', 'rVert', 'colon', 'forall',
  'exists', 'nexists', 'bigl', 'bigr', 'Bigl', 'Bigr', 'big', 'Big', 'bigg', 'Bigg',
  'phantom', 'substack', 'array', 'aligned', 'gathered', 'cases', 'matrix', 'pmatrix',
  'bmatrix', 'vmatrix', 'Vmatrix', 'smallmatrix', 'color', 'textcolor', 'displaystyle',
  'mathrm', 'mathbb', 'mathcal', 'mathbf', 'mathsf', 'textstyle', 'limits', 'nolimits'
].sort((a, b) => b.length - a.length)

const LATEX_COMMAND_SET = new Set(LATEX_COMMANDS)

/**
 * 在“命令 + 紧跟的字母”之间补空格，例如 \Rightarrowp -> \Rightarrow p、\inA -> \in A。
 * KaTeX 里命令名到非字母才结束，所以 \inA 会被当成未知命令直接报错。
 * 做法：取出反斜杠后连续的字母串，若它不是完整命令，则拆出最长已知命令前缀。
 */
export function fixConcatenatedCommands(text) {
  return text.replace(/\\([A-Za-z]+)/g, (match, word) => {
    if (LATEX_COMMAND_SET.has(word)) return match
    const prefix = LATEX_COMMANDS.find((cmd) => word.length > cmd.length && word.startsWith(cmd))
    if (!prefix) return match
    return `\\${prefix} ${word.slice(prefix.length)}`
  })
}

/** 公式片段（含定界符），用于渲染前做保护 */
const MATH_SPAN_RE = /(\$\$[\s\S]*?\$\$|\$[^$\n]*\$|\\\[[\s\S]*?\\\]|\\\([\s\S]*?\\\))/g

/** 行首的选项标记，例如 "A. "、"3)" */
const OPTION_PREFIX_RE = /^(\s*(?:[A-Da-d]|[0-9]+)[.、．)]\s*)(.+)$/

const LATEX_HINT_RE = /\\(frac|sqrt|left|right|begin|end|sum|int|iint|oint|prod|lim|sin|cos|tan|log|ln|alpha|beta|gamma|theta|pi|Delta|infty|rightarrow|leftarrow|Rightarrow|Leftrightarrow|leq|geq|neq|ne|ge|le|gg|ll|cdot|times|div|over|overline|underline|bar|hat|tilde|vec|matrix|cases|binom|exists|forall|mathbb|mathrm|mathcal|implies|iff|subset|cup|cap|in|notin|emptyset|pm|mp|root|limits|bigcup|bigcap|to|aleph|angle|perp|parallel|sim|cong|equiv|approx|propto|mid|vert|colon|quad|qquad)/

/**
 * 逐行为“裸公式”补上 $...$。
 * AI 有时只在题干里写了 $...$，选项却直接裸写 \left\{1,\frac{2}{3}\right\}，
 * 这种情况前面“整段无 $ 才兜底”的判断会漏掉，导致选项显示红色源码。
 */
function wrapBareMathLines(text) {
  return text
    .split('\n')
    .map((line) => {
      const trimmed = line.trim()
      if (!trimmed || trimmed.includes('$')) return line
      // 含中文说明是叙述性文字，不整体包成公式
      if (/[\u4e00-\u9fa5]/.test(trimmed)) return line
      if (!LATEX_HINT_RE.test(trimmed) && !/[\^_]/.test(trimmed)) return line
      const m = trimmed.match(OPTION_PREFIX_RE)
      if (m) return `${m[1]}$${m[2].trim()}$`
      return `$${trimmed}$`
    })
    .join('\n')
}

/**
 * markdown-it 会把 \{ \} \_ 这类“反斜杠 + 标点”当成转义处理并吃掉反斜杠，
 * 于是 \left\{1,\frac{2}{3}\right\} 变成 \left{...\right}，KaTeX 直接报错。
 * 这里先把公式片段替换成占位符，markdown 渲染完再原样还原。
 */
export function renderMarkdown(md, text) {
  const spans = []
  const masked = text.replace(MATH_SPAN_RE, (match) => {
    spans.push(match)
    return `@@MATH${spans.length - 1}@@`
  })

  let html = md.render(masked)
  spans.forEach((span, i) => {
    const escaped = span.replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;')
    html = html.split(`@@MATH${i}@@`).join(escaped)
  })
  return html
}

/**
 * 兜底：AI 有时输出纯 LaTeX 但没加 $...$ 包裹（例如整个选项就是 \frac{1}{2}）。
 */
export function normalizeLatex(text) {
  if (!text) return text

  // AI 偶尔把命令和变量粘在一起（\inA），先补空格再交给 KaTeX
  text = fixConcatenatedCommands(text)

  // 先把 $$...$$ 块级公式内部的换行压缩成空格，避免 markdown 拆成多个 <p>，
  // 导致 KaTeX auto-render 找不到成对的 $$ 定界符而显示源码。
  text = text.replace(/\$\$[\s\S]*?\$\$/g, (match) => match.replace(/\n/g, ' '))

  if (!text.includes('$')) {
    // 含中文说明是叙述性文字，整体包成公式反而会渲染失败，改走逐行兜底
    if (!/[\u4e00-\u9fa5]/.test(text)) {
      const hasSupOrSub = /[\^_]\{?[0-9a-zA-Z]/.test(text)
      if (LATEX_HINT_RE.test(text) || hasSupOrSub) return `$${text.replace(/\n/g, ' ')}$`
    }
  }
  return wrapBareMathLines(text)
}
