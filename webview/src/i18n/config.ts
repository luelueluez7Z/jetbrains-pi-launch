import i18n from 'i18next';
import { initReactI18next } from 'react-i18next';
import zh from './locales/zh.json';

// 精简版：固定简体中文，不支持多语言。
i18n
  .use(initReactI18next) // Integrate i18n with React
  .init({
    resources: {
      zh: { translation: zh }, // Simplified Chinese
    },
    lng: 'zh',
    fallbackLng: 'zh',
    interpolation: {
      escapeValue: false, // React already handles XSS protection
    },
  });

export default i18n;
