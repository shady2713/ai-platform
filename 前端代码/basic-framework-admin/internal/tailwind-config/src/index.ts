import type { Config } from 'tailwindcss';

const colors = {
  accent: {
    DEFAULT: 'hsl(var(--accent))',
    foreground: 'hsl(var(--accent-foreground))',
    hover: 'hsl(var(--accent-hover))',
    lighter: 'hsl(var(--accent-lighter))',
  },
  background: {
    DEFAULT: 'hsl(var(--background))',
    deep: 'hsl(var(--background-deep))',
  },
  border: {
    DEFAULT: 'hsl(var(--border))',
  },
  card: {
    DEFAULT: 'hsl(var(--card))',
    foreground: 'hsl(var(--card-foreground))',
  },
  destructive: {
    DEFAULT: 'hsl(var(--destructive))',
    foreground: 'hsl(var(--destructive-foreground))',
  },
  foreground: {
    DEFAULT: 'hsl(var(--foreground))',
  },
  header: {
    DEFAULT: 'hsl(var(--header))',
  },
  heavy: {
    DEFAULT: 'hsl(var(--heavy))',
    foreground: 'hsl(var(--heavy-foreground))',
  },
  input: {
    DEFAULT: 'hsl(var(--input))',
    background: 'hsl(var(--input-background))',
  },
  main: {
    DEFAULT: 'hsl(var(--main))',
  },
  muted: {
    DEFAULT: 'hsl(var(--muted))',
    foreground: 'hsl(var(--muted-foreground))',
  },
  overlay: {
    DEFAULT: 'hsl(var(--overlay))',
    content: 'hsl(var(--overlay-content))',
  },
  popover: {
    DEFAULT: 'hsl(var(--popover))',
    foreground: 'hsl(var(--popover-foreground))',
  },
  primary: {
    DEFAULT: 'hsl(var(--primary))',
    foreground: 'hsl(var(--primary-foreground))',
    hover: 'hsl(var(--primary-600, var(--primary)))',
    active: 'hsl(var(--primary-700, var(--primary)))',
  },
  ring: 'hsl(var(--ring))',
  secondary: {
    DEFAULT: 'hsl(var(--secondary))',
    foreground: 'hsl(var(--secondary-foreground))',
    desc: 'hsl(var(--secondary-desc, var(--secondary-foreground)))',
  },
  sidebar: {
    DEFAULT: 'hsl(var(--sidebar))',
    deep: 'hsl(var(--sidebar-deep))',
  },
  success: {
    DEFAULT: 'hsl(var(--success))',
    foreground: 'hsl(var(--success-foreground))',
  },
  warning: {
    DEFAULT: 'hsl(var(--warning))',
    foreground: 'hsl(var(--warning-foreground))',
  },
};

const config: Config = {
  content: {
    relative: true,
    files: [
      '../../apps/**/index.html',
      '../../apps/**/src/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '../../apps/**/dist/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '../../packages/**/src/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '../../packages/**/dist/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '../../internal/**/src/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '../../internal/**/dist/**/*.{vue,js,ts,jsx,tsx,html,css,scss}',
      '!../../apps/**/node_modules/**',
      '!../../packages/**/node_modules/**',
      '!../../internal/**/node_modules/**',
    ],
  },
  darkMode: 'selector',
  plugins: [],
  prefix: '',
  safelist: ['dark'],
  theme: {
    extend: {
      borderRadius: {
        lg: 'var(--radius)',
        md: 'calc(var(--radius) - 2px)',
        sm: 'calc(var(--radius) - 4px)',
      },
      colors,
      fontFamily: {
        sans: ['var(--font-family)'],
      },
    },
  },
};

export default config;
