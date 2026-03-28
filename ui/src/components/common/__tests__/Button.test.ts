import { describe, it, expect } from 'vitest';
import { mount } from '@vue/test-utils';
import Button from '../Button.vue';

describe('Button', () => {
  it('renders default slot content', () => {
    const wrapper = mount(Button, {
      slots: {
        default: 'Click Me'
      }
    });
    expect(wrapper.text()).toBe('Click Me');
  });

  it('applies primary variant class', () => {
    const wrapper = mount(Button, {
      props: {
        variant: 'primary'
      }
    });
    expect(wrapper.classes()).toContain('bg-primary');
  });

  it('applies secondary variant class', () => {
    const wrapper = mount(Button, {
      props: {
        variant: 'secondary'
      }
    });
    expect(wrapper.classes()).toContain('bg-secondary');
  });

  it('applies disabled attribute when disabled prop is true', () => {
    const wrapper = mount(Button, {
      props: {
        disabled: true
      }
    });
    expect(wrapper.attributes('disabled')).toBeDefined();
  });

  it('emits click event when clicked', async () => {
    const wrapper = mount(Button);
    await wrapper.trigger('click');
    expect(wrapper.emitted('click')).toHaveLength(1);
  });
});
