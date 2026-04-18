(function () {
  const removeDevTools = () => {
    document.querySelectorAll("vaadin-dev-tools, copilot-main").forEach((node) => node.remove());
  };

  removeDevTools();

  const observer = new MutationObserver(() => removeDevTools());
  observer.observe(document.documentElement, { childList: true, subtree: true });

  window.addEventListener("load", removeDevTools);
})();
