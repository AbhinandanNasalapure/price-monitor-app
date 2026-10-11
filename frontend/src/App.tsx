import { useCallback, useEffect, useMemo, useState, type FormEvent } from 'react';
import { api } from './api';
import type { Product, ProductInput } from './types';

const emptyForm: ProductInput = { name: '', url: '', targetPrice: '', currency: 'USD', priceSelector: '' };
const currency = (amount: number | null, code: string) => amount == null
  ? '—'
  : new Intl.NumberFormat(undefined, { style: 'currency', currency: code }).format(amount);
const date = (value: string | null) => value
  ? new Intl.DateTimeFormat(undefined, { dateStyle: 'medium', timeStyle: 'short' }).format(new Date(value))
  : 'Not checked yet';

export default function App() {
  const [products, setProducts] = useState<Product[]>([]);
  const [form, setForm] = useState(emptyForm);
  const [loading, setLoading] = useState(true);
  const [saving, setSaving] = useState(false);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState('');
  const [showForm, setShowForm] = useState(false);

  const refresh = useCallback(async () => {
    try { setProducts(await api.products()); setError(''); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not load products.'); }
    finally { setLoading(false); }
  }, []);

  useEffect(() => { void refresh(); }, [refresh]);

  const stats = useMemo(() => {
    const checked = products.filter((product) => product.currentPrice != null);
    const dropped = checked.filter((product) => product.history.length > 1
      && product.currentPrice! < product.history[1].amount).length;
    return { tracked: products.length, checked: checked.length, dropped };
  }, [products]);

  async function addProduct(event: FormEvent<HTMLFormElement>) {
    event.preventDefault(); setSaving(true); setError('');
    try {
      const created = await api.create(form);
      setProducts((current) => [created, ...current]); setForm(emptyForm); setShowForm(false);
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not add this product.'); }
    finally { setSaving(false); }
  }

  async function checkProduct(id: number) {
    setBusyId(id); setError('');
    try {
      const updated = await api.check(id);
      setProducts((current) => current.map((product) => product.id === id ? updated : product));
    } catch (reason) { setError(reason instanceof Error ? reason.message : 'Price check failed.'); }
    finally { setBusyId(null); }
  }

  async function deleteProduct(id: number) {
    if (!window.confirm('Remove this product and its price history?')) return;
    try { await api.remove(id); setProducts((current) => current.filter((product) => product.id !== id)); }
    catch (reason) { setError(reason instanceof Error ? reason.message : 'Could not remove this product.'); }
  }

  return (
    <main className="shell">
      <header className="topbar">
        <a className="brand" href="/" aria-label="Price Monitor home"><span className="brand-mark">$</span><span>price<span className="brand-light">monitor</span></span></a>
        <div className="top-actions"><span className="live-dot" /> Local workspace <button className="avatar" aria-label="Account">A</button></div>
      </header>

      <section className="hero">
        <div><div className="eyebrow">YOUR WATCHLIST</div><h1>Make every price<br /><span>drop count.</span></h1>
          <p>One calm place to follow the products you want.</p></div>
        <button className="primary-button" onClick={() => setShowForm((open) => !open)}><span className="plus">+</span> Track a product</button>
      </section>

      <section className="stat-grid" aria-label="Watchlist overview">
        <Stat label="TRACKED PRODUCTS" value={stats.tracked.toString().padStart(2, '0')} note="Across your watchlist" />
        <Stat label="PRICES FOUND" value={stats.checked.toString().padStart(2, '0')} note="With a current price" />
        <Stat label="PRICE DROPS" value={stats.dropped.toString().padStart(2, '0')} note="Since the last check" accent />
      </section>

      {showForm && <form className="add-card" onSubmit={addProduct}>
        <div className="form-heading"><div><div className="eyebrow">NEW TRACKER</div><h2>Add a product</h2></div><button type="button" className="icon-button" onClick={() => setShowForm(false)} aria-label="Close">×</button></div>
        <div className="form-grid">
          <label>Product name<input required maxLength={160} placeholder="e.g. Wireless headphones" value={form.name} onChange={(e) => setForm({ ...form, name: e.target.value })} /></label>
          <label>Product page URL<input required type="url" placeholder="https://store.example/product" value={form.url} onChange={(e) => setForm({ ...form, url: e.target.value })} /></label>
          <label>Target price <span className="optional">optional</span><input type="number" min="0.01" step="0.01" placeholder="150.00" value={form.targetPrice} onChange={(e) => setForm({ ...form, targetPrice: e.target.value })} /></label>
          <label>Currency<input required minLength={3} maxLength={3} value={form.currency} onChange={(e) => setForm({ ...form, currency: e.target.value.toUpperCase() })} /></label>
          <label className="wide">Price CSS selector <span className="optional">optional · for pages with unusual layouts</span><input maxLength={128} placeholder=".price, [data-testid='price']" value={form.priceSelector} onChange={(e) => setForm({ ...form, priceSelector: e.target.value })} /></label>
        </div>
        <div className="form-footer"><span>We’ll check the page now and every 30 minutes.</span><button className="primary-button" disabled={saving}>{saving ? 'Checking…' : 'Add to watchlist'}</button></div>
      </form>}

      {error && <div className="alert" role="alert"><span>{error}</span><button onClick={() => setError('')} aria-label="Dismiss">×</button></div>}

      <section className="watchlist">
        <div className="section-heading"><div><div className="eyebrow">THE LINEUP</div><h2>Your watchlist</h2></div><span className="count-pill">{products.length} {products.length === 1 ? 'item' : 'items'}</span></div>
        {loading ? <div className="empty-state">Loading your watchlist…</div> : products.length === 0 ? <div className="empty-state"><div className="empty-icon">↘</div><h3>Your watchlist is a blank slate.</h3><p>Add a product URL and we’ll start keeping an eye on its price.</p><button className="secondary-button" onClick={() => setShowForm(true)}>Add your first product</button></div> :
          <div className="product-list">{products.map((product) => <ProductCard key={product.id} product={product} busy={busyId === product.id} onCheck={checkProduct} onDelete={deleteProduct} />)}</div>}
      </section>
      <footer><span>PRICE MONITOR</span><span>Private by default · Runs on your machine</span></footer>
    </main>
  );
}

function Stat({ label, value, note, accent = false }: { label: string; value: string; note: string; accent?: boolean }) {
  return <article className={`stat-card${accent ? ' stat-accent' : ''}`}><div className="eyebrow">{label}</div><div className="stat-value">{value}</div><div className="stat-note">{note}</div></article>;
}

function ProductCard({ product, busy, onCheck, onDelete }: { product: Product; busy: boolean; onCheck: (id: number) => void; onDelete: (id: number) => void }) {
  const previous = product.history[1]?.amount;
  const trend = previous != null && product.currentPrice != null
    ? product.currentPrice < previous ? 'down' : product.currentPrice > previous ? 'up' : 'steady'
    : 'new';
  const change = previous != null && product.currentPrice != null ? Math.abs(product.currentPrice - previous) : null;
  return <article className="product-card">
    <div className="product-visual">{product.imageUrl ? <img src={product.imageUrl} alt="" /> : <div className="visual-placeholder">◇</div>}</div>
    <div className="product-main"><div className="product-title-row"><a href={product.url} target="_blank" rel="noreferrer" className="product-title">{product.name}</a>{product.targetPrice != null && <span className="target-chip">Target {currency(product.targetPrice, product.currency)}</span>}</div>
      <div className="product-url">{new URL(product.url).hostname}</div>
      {product.lastCheckError && <div className="product-error">{product.lastCheckError}</div>}
      <div className="product-meta"><span>{product.history.length} checks</span><span className="meta-separator">·</span><span>Last checked {date(product.lastCheckedAt)}</span></div>
    </div>
    <div className="product-price"><div className="eyebrow">CURRENT PRICE</div><div className="price-value">{currency(product.currentPrice, product.currency)}</div>
      <div className={`trend trend-${trend}`}>{trend === 'down' ? '↘' : trend === 'up' ? '↗' : trend === 'steady' ? '→' : '•'} {change == null ? 'Awaiting first reading' : `${currency(change, product.currency)} since previous check`}</div></div>
    <div className="product-actions"><button className="icon-button" onClick={() => onCheck(product.id)} disabled={busy} title="Check price now" aria-label="Check price now">{busy ? '…' : '↻'}</button><button className="icon-button delete-button" onClick={() => onDelete(product.id)} title="Remove product" aria-label="Remove product">×</button></div>
  </article>;
}
