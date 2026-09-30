// Progressive enhancement only. Authentication tokens are never used by browser JS.
document.querySelectorAll('form').forEach(form => {
  form.addEventListener('submit', () => {
    const button = form.querySelector('button[type="submit"],button:not([type])');
    if (button) { button.disabled = true; button.textContent = 'Подождите…'; }
    form.setAttribute('aria-busy', 'true');
  });
});
window.addEventListener('pageshow', () => {
  document.querySelectorAll('form[aria-busy]').forEach(form => {
    form.removeAttribute('aria-busy');
    form.querySelectorAll('button').forEach(button => { button.disabled = false; });
  });
});
const createLink = document.querySelector('a[href="#create-for-user"]');
if (createLink) createLink.addEventListener('click', () => {
  document.querySelector('#create-for-user').open = true;
});

