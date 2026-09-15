<%@ page contentType="text/html;charset=UTF-8" language="java" %>
<!DOCTYPE html>
<html lang="en">
<head>
    <meta charset="UTF-8">
    <meta name="viewport" content="width=device-width, initial-scale=1.0">
    <meta name="description" content="Cyfuture DBaaS - secure, scalable, fully managed databases.">
    <title>Cyfuture DBaaS</title>
    <link rel="stylesheet" href="${pageContext.request.contextPath}/css/dbaas-home.css">
</head>
<body>
<header class="topbar">
    <button class="icon-button menu-button" type="button" aria-label="Open navigation" aria-expanded="false">
        <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M4 6h16M4 12h16M4 18h16"/></svg>
    </button>
    <a class="brand" href="${pageContext.request.contextPath}/" aria-label="Cyfuture DBaaS home">
        <span class="brand-mark">
            <svg viewBox="0 0 28 28" aria-hidden="true"><path d="M8 5l10 9L8 23M14 5l10 9-10 9"/></svg>
        </span>
        <span>Cyfuture<span class="brand-dot">.ai</span></span>
    </a>
    <div class="top-actions">
        <button class="theme-toggle" type="button" aria-label="Switch theme"><span>☼</span></button>
        <a class="wallet" href="#pricing" aria-label="Account balance">
            <svg viewBox="0 0 24 24" aria-hidden="true"><path d="M3 6.5A2.5 2.5 0 0 1 5.5 4H18v4H6a3 3 0 0 0 0 6h14v5H5a2 2 0 0 1-2-2V6.5Z"/><path d="M6 8h14a1 1 0 0 1 1 1v5H6a3 3 0 0 1 0-6Z"/><circle cx="17.5" cy="11" r="1"/></svg>
            <span><small>Balance</small><strong>₹ 534.80</strong></span>
        </a>
        <button class="icon-button apps-button" type="button" aria-label="Open applications">
            <svg viewBox="0 0 24 24" aria-hidden="true"><circle cx="6" cy="6" r="1"/><circle cx="12" cy="6" r="1"/><circle cx="18" cy="6" r="1"/><circle cx="6" cy="12" r="1"/><circle cx="12" cy="12" r="1"/><circle cx="18" cy="12" r="1"/><circle cx="6" cy="18" r="1"/><circle cx="12" cy="18" r="1"/><circle cx="18" cy="18" r="1"/></svg>
        </button>
    </div>
</header>

<aside class="side-drawer" aria-label="Primary navigation">
    <a href="#overview">Overview</a>
    <a href="#features">Features</a>
    <a href="#engines">Database engines</a>
    <a href="${pageContext.request.contextPath}/swagger-ui.html">API documentation</a>
</aside>

<main>
    <section class="welcome" id="overview" aria-labelledby="welcome-title">
        <p class="eyebrow">Get Started</p>
        <div class="hero-card">
            <div class="hero-content">
                <span class="product-label">DATABASE AS A SERVICE</span>
                <h1 id="welcome-title">Managed Databases.<br><span>Built to Scale.</span></h1>
                <p>Deploy production-ready PostgreSQL, MySQL, and MongoDB in minutes. Automate provisioning, scaling, backups, and recovery while Cyfuture DBaaS manages the infrastructure for you.</p>
                <div class="hero-actions">
                    <a class="button button-primary" href="#engines">Create Database</a>
                    <a class="button button-secondary" href="${pageContext.request.contextPath}/swagger-ui.html">Explore API</a>
                    <a class="button button-secondary" href="#pricing">View Plans</a>
                </div>
            </div>
            <div class="hero-art" aria-hidden="true">
                <span class="orbit orbit-one"></span><span class="orbit orbit-two"></span>
                <div class="cloud"><span></span><i></i><b></b></div>
                <div class="db-stack db-one"><span></span><span></span><span></span></div>
                <div class="db-stack db-two"><span></span><span></span><span></span></div>
                <svg class="data-lines" viewBox="0 0 430 225"><path d="M29 190h95l38-46h57l45-59h107"/><path d="M66 213h106l43-45h50l62-79"/><circle cx="124" cy="190" r="5"/><circle cx="219" cy="144" r="5"/><circle cx="264" cy="85" r="5"/></svg>
            </div>
        </div>
    </section>

    <section class="features" id="features" aria-labelledby="features-title">
        <div class="section-heading">
            <p class="eyebrow">WHY CYFUTURE DBAAS</p>
            <h2 id="features-title">Everything Your Database Needs</h2>
            <p>Powerful database operations made simple, reliable, and secure.</p>
        </div>
        <div class="feature-grid">
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><ellipse cx="16" cy="7" rx="10" ry="4"/><path d="M6 7v8c0 2 4.5 4 10 4s10-2 10-4V7M6 15v8c0 2 4.5 4 10 4s10-2 10-4v-8"/><path d="m20 13 3 3 5-6"/></svg></div>
                <h3>Fully Managed</h3>
                <p>Provision, patch, monitor, and maintain databases without managing the underlying Kubernetes infrastructure.</p>
                <a href="#engines">Explore engines <span>→</span></a>
            </article>
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><path d="M5 25V12M14 25V7M23 25V3M3 25h26"/><path d="m5 11 8-5 9 2 6-5"/></svg></div>
                <h3>Scale on Demand</h3>
                <p>Increase compute, storage, or replicas as workloads grow, with flexible vertical and horizontal scaling.</p>
                <a href="${pageContext.request.contextPath}/swagger-ui.html">Scaling API <span>→</span></a>
            </article>
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><path d="M16 3 27 7v8c0 7-4.6 11.3-11 14-6.4-2.7-11-7-11-14V7l11-4Z"/><path d="m11 16 3 3 7-8"/></svg></div>
                <h3>Secure by Design</h3>
                <p>Protect every deployment with isolated projects, managed credentials, access controls, and secure endpoints.</p>
                <a href="#security">Security details <span>→</span></a>
            </article>
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><path d="M8 24a11 11 0 1 1 17.5-3"/><path d="M23 14v8h7M16 9v7l5 3"/></svg></div>
                <h3>Backup &amp; Recovery</h3>
                <p>Schedule full and incremental backups, define retention, and restore to a precise point in time.</p>
                <a href="${pageContext.request.contextPath}/swagger-ui.html">Recovery options <span>→</span></a>
            </article>
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><path d="M4 25h24M7 21V11M13 21V5M19 21v-7M25 21V8"/><path d="m5 8 7-5 7 7 8-6"/></svg></div>
                <h3>High Availability</h3>
                <p>Run resilient database topologies with health monitoring, replication, failover, and state reconciliation.</p>
                <a href="#availability">Learn more <span>→</span></a>
            </article>
            <article class="feature-card">
                <div class="feature-icon"><svg viewBox="0 0 32 32"><path d="M6 17a10 10 0 1 1 20 0v7M6 18H3v7h6v-7H6Zm20 0h3v7h-6v-7h3ZM23 27c-2 2-4 2-7 2"/></svg></div>
                <h3>24/7 Expert Support</h3>
                <p>Get dependable operational assistance for database provisioning, connectivity, backups, and recovery.</p>
                <a href="mailto:support@cyfuture.com">Contact support <span>→</span></a>
            </article>
        </div>
    </section>

    <section class="engines" id="engines" aria-labelledby="engines-title">
        <div><p class="eyebrow">SUPPORTED ENGINES</p><h2 id="engines-title">Choose the Right Database</h2></div>
        <div class="engine-list"><span>PostgreSQL</span><span>MySQL</span><span>MongoDB</span></div>
    </section>
</main>

<script>
    const menuButton = document.querySelector('.menu-button');
    const drawer = document.querySelector('.side-drawer');
    menuButton.addEventListener('click', () => {
        const open = document.body.classList.toggle('menu-open');
        menuButton.setAttribute('aria-expanded', String(open));
    });
    drawer.querySelectorAll('a').forEach(link => link.addEventListener('click', () => {
        document.body.classList.remove('menu-open');
        menuButton.setAttribute('aria-expanded', 'false');
    }));
    document.querySelector('.theme-toggle').addEventListener('click', () => {
        document.body.classList.toggle('dark');
    });
</script>
</body>
</html>
