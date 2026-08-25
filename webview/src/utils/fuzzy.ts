/**
 * Fuzzy matching utilities（移植自 pi-tui 的 dist/fuzzy.js，MIT）。
 *
 * 与 pi 的模型搜索同款算法：query 的所有字符按顺序出现即可命中（可跨字符跳跃），
 * 支持空格/斜杠分隔的多 token（每个 token 都必须匹配），并按相关度打分排序。
 * 用于模型选择器搜索，对齐 pi 本身的模糊过滤体验。
 */

/** 单个 token 的模糊匹配结果。 */
export interface FuzzyMatchResult {
  matches: boolean;
  score: number;
}

/**
 * 模糊匹配单个查询串（不含空格拆分）：
 * - query 的每个字符在 text 中按顺序出现即匹配（不要求连续）
 * - 连续匹配、词边界（[\s\-_./:] 之前）命中得分更高；间隔越远扣分越多
 * - 完全相等额外加分；支持字母/数字互换（如 gpt5 ↔ gpt-5）
 * - 分数越低代表匹配越好
 */
export function fuzzyMatch(query: string, text: string): FuzzyMatchResult {
  const queryLower = query.toLowerCase();
  const textLower = text.toLowerCase();

  const matchQuery = (normalizedQuery: string): FuzzyMatchResult => {
    if (normalizedQuery.length === 0) {
      return { matches: true, score: 0 };
    }
    if (normalizedQuery.length > textLower.length) {
      return { matches: false, score: 0 };
    }
    let queryIndex = 0;
    let score = 0;
    let lastMatchIndex = -1;
    let consecutiveMatches = 0;
    for (let i = 0; i < textLower.length && queryIndex < normalizedQuery.length; i++) {
      if (textLower[i] === normalizedQuery[queryIndex]) {
        const isWordBoundary = i === 0 || /[\s\-_./:]/.test(textLower[i - 1]);
        // 连续匹配加分
        if (lastMatchIndex === i - 1) {
          consecutiveMatches++;
          score -= consecutiveMatches * 5;
        } else {
          consecutiveMatches = 0;
          // 间隔扣分
          if (lastMatchIndex >= 0) {
            score += (i - lastMatchIndex - 1) * 2;
          }
        }
        // 词边界命中加分
        if (isWordBoundary) {
          score -= 10;
        }
        // 越靠后轻微扣分
        score += i * 0.1;
        lastMatchIndex = i;
        queryIndex++;
      }
    }
    if (queryIndex < normalizedQuery.length) {
      return { matches: false, score: 0 };
    }
    if (normalizedQuery === textLower) {
      score -= 100;
    }
    return { matches: true, score };
  };

  const primaryMatch = matchQuery(queryLower);
  if (primaryMatch.matches) {
    return primaryMatch;
  }
  // 字母/数字互换尝试：如 "gpt5" ↔ "gpt-5"、"5gpt"
  const alphaNumericMatch = queryLower.match(/^(?<letters>[a-z]+)(?<digits>[0-9]+)$/);
  const numericAlphaMatch = queryLower.match(/^(?<digits>[0-9]+)(?<letters>[a-z]+)$/);
  const swappedQuery = alphaNumericMatch
    ? `${alphaNumericMatch.groups?.digits ?? ''}${alphaNumericMatch.groups?.letters ?? ''}`
    : numericAlphaMatch
      ? `${numericAlphaMatch.groups?.letters ?? ''}${numericAlphaMatch.groups?.digits ?? ''}`
      : '';
  if (!swappedQuery) {
    return primaryMatch;
  }
  const swappedMatch = matchQuery(swappedQuery);
  if (!swappedMatch.matches) {
    return primaryMatch;
  }
  return { matches: true, score: swappedMatch.score + 5 };
}

/**
 * 按模糊匹配质量过滤并排序（最佳匹配在前）。
 * query 按空格/斜杠拆分为多个 token，所有 token 都必须命中；总得分越低排名越靠前。
 */
export function fuzzyFilter<T>(items: T[], query: string, getText: (item: T) => string): T[] {
  if (!query.trim()) {
    return items;
  }
  const tokens = query
    .trim()
    .split(/[\s/]+/)
    .filter((t) => t.length > 0);
  if (tokens.length === 0) {
    return items;
  }
  const results: { item: T; totalScore: number }[] = [];
  for (const item of items) {
    const text = getText(item);
    let totalScore = 0;
    let allMatch = true;
    for (const token of tokens) {
      const match = fuzzyMatch(token, text);
      if (match.matches) {
        totalScore += match.score;
      } else {
        allMatch = false;
        break;
      }
    }
    if (allMatch) {
      results.push({ item, totalScore });
    }
  }
  results.sort((a, b) => a.totalScore - b.totalScore);
  return results.map((r) => r.item);
}
