/* A failed diagram remains visible as text and is detectable by the browser smoke test. */
(async () => {
  try {
    if (document.querySelector('.mermaid')) {
      mermaid.initialize({startOnLoad: false, securityLevel: 'strict', theme: 'neutral', flowchart: {useMaxWidth: true}});
      await mermaid.run({querySelector: '.mermaid'});
    }
    document.documentElement.dataset.diagrams = 'ready';
  } catch (error) {
    document.documentElement.dataset.diagrams = 'failed';
    console.error('Diagram rendering failed', error);
  }
})();
