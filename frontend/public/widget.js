/*
 * FakeJIRA feedback widget. Add to any page:
 *   <script src="https://your-fakejira/widget.js" data-project="KEY" async></script>
 * Optional: data-label="Feedback", data-position="left" (default right), data-color="#4f46e5".
 * The form runs in an iframe served by FakeJIRA, so the host page never sees what people type.
 */
(function () {
  var script = document.currentScript;
  if (!script || window.__fakejiraWidget) return;
  window.__fakejiraWidget = true;
  var project = script.getAttribute('data-project');
  if (!project) {
    console.warn('FakeJIRA widget: add data-project="KEY" to the script tag.');
    return;
  }
  var origin = new URL(script.src, window.location.href).origin;
  var side = script.getAttribute('data-position') === 'left' ? 'left' : 'right';
  var color = script.getAttribute('data-color') || '#4f46e5';
  var label = script.getAttribute('data-label') || 'Feedback';

  var button = document.createElement('button');
  button.type = 'button';
  button.textContent = label;
  button.setAttribute('aria-haspopup', 'dialog');
  button.setAttribute('aria-expanded', 'false');
  button.style.cssText = 'position:fixed;bottom:20px;' + side + ':20px;z-index:2147483000;padding:10px 16px;border:0;'
    + 'border-radius:999px;background:' + color + ';color:#fff;font:600 14px/1.2 system-ui,sans-serif;cursor:pointer;'
    + 'box-shadow:0 6px 20px rgba(0,0,0,.2)';

  var frame = null;
  function close() {
    if (frame) {
      frame.remove();
      frame = null;
      button.setAttribute('aria-expanded', 'false');
      button.focus();
    }
  }
  function open() {
    if (frame) return close();
    frame = document.createElement('iframe');
    frame.src = origin + '/embed/' + encodeURIComponent(project);
    frame.title = label;
    frame.style.cssText = 'position:fixed;bottom:76px;' + side + ':20px;z-index:2147483000;width:380px;height:560px;'
      + 'max-width:calc(100vw - 40px);max-height:calc(100vh - 100px);border:0;border-radius:14px;background:#fff;'
      + 'box-shadow:0 12px 40px rgba(0,0,0,.25)';
    document.body.appendChild(frame);
    button.setAttribute('aria-expanded', 'true');
  }
  button.addEventListener('click', open);
  window.addEventListener('message', function (event) {
    if (event.origin === origin && event.data && event.data.type === 'fakejira-widget-close') close();
  });
  document.addEventListener('keydown', function (event) {
    if (event.key === 'Escape') close();
  });
  (document.body ? Promise.resolve() : new Promise(function (r) { document.addEventListener('DOMContentLoaded', r); }))
    .then(function () { document.body.appendChild(button); });
})();
