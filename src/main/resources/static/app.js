// Progressive enhancement only. Authentication tokens are never used by browser JS.
const originalButtonText = new WeakMap();
document.querySelectorAll('form').forEach(form => {
  form.addEventListener('submit', () => {
    const button = form.querySelector('button[type="submit"],button:not([type])');
    if (button) {
      originalButtonText.set(button, button.textContent);
      button.disabled = true; button.textContent = 'Подождите…';
    }
    form.setAttribute('aria-busy', 'true');
  });
});
window.addEventListener('pageshow', () => {
  document.querySelectorAll('form[aria-busy]').forEach(form => {
    form.removeAttribute('aria-busy');
    form.querySelectorAll('button').forEach(button => {
      button.disabled = false;
      if (originalButtonText.has(button)) button.textContent = originalButtonText.get(button);
    });
  });
});
const createLink = document.querySelector('a[href="#create-for-user"]');
if (createLink) createLink.addEventListener('click', () => {
  document.querySelector('#create-for-user').open = true;
});

