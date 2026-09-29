/** Where a saved filter opens: query-language filters go to Search, simple ones to the project backlog. */
export function filterPath(projectKey: string, query: string) {
  return query.startsWith('fql=') ? `/search?q=${query.slice(4)}` : `/p/${projectKey}/backlog?${query}`;
}
