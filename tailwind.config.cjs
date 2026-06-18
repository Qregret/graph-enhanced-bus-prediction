module.exports = {
  content: ['./index.html', './src/**/*.{vue,js,ts,jsx,tsx}'],
  corePlugins: {
    preflight: false
  },
  theme: {
    extend: {
      boxShadow: {
        soft: '0 18px 45px rgba(15, 23, 42, 0.08)',
        card: '0 10px 30px rgba(15, 23, 42, 0.06)'
      },
      colors: {
        brand: {
          50: '#eef6ff',
          100: '#dbeafe',
          500: '#3b82f6',
          600: '#2563eb',
          700: '#1d4ed8'
        }
      },
      keyframes: {
        'pulse-soft': {
          '0%, 100%': { transform: 'scale(1)', opacity: '1' },
          '50%': { transform: 'scale(1.08)', opacity: '.72' }
        }
      },
      animation: {
        'pulse-soft': 'pulse-soft 1.8s ease-in-out infinite'
      }
    }
  },
  plugins: []
}
