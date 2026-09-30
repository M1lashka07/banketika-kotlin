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
// Supabase's default confirmation email can return tokens in the fragment.
// Discard them and ask for a normal password login; do not claim a valid session.
if (location.pathname === '/login' && location.hash) {
  const hasError = new URLSearchParams(location.hash.slice(1)).has('error');
  history.replaceState(null, '', '/login');
  const message = document.createElement('div');
  message.className = hasError ? 'notice error' : 'notice';
  message.setAttribute('role', 'status');
  message.textContent = hasError ? 'Ссылка подтверждения недействительна или устарела. Попробуйте зарегистрироваться снова.' : 'Email подтверждён. Войдите с вашим паролем.';
  document.querySelector('.auth-card form')?.before(message);
}
