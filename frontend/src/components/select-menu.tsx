'use client';

import {useEffect, useId, useRef, useState} from 'react';
import {Check, ChevronDown} from 'lucide-react';

type SelectMenuOption = {
    value: string;
    label: string;
};

type SelectMenuProps = {
    ariaLabel: string;
    value: string;
    options: SelectMenuOption[];
    onChange: (value: string) => void;
    className?: string;
    disabled?: boolean;
};

export default function SelectMenu({
    ariaLabel,
    value,
    options,
    onChange,
    className = '',
    disabled = false,
}: SelectMenuProps) {
    const rootRef = useRef<HTMLDivElement>(null);
    const triggerRef = useRef<HTMLButtonElement>(null);
    const optionRefs = useRef<Array<HTMLButtonElement | null>>([]);
    const listboxId = useId();
    const [isOpen, setIsOpen] = useState(false);
    const [focusedIndex, setFocusedIndex] = useState(0);
    const selectedIndex = Math.max(0, options.findIndex((option) => option.value === value));
    const selectedOption = options[selectedIndex];

    useEffect(() => {
        if (!isOpen) return;

        const closeWhenClickingOutside = (event: PointerEvent) => {
            if (!rootRef.current?.contains(event.target as Node)) {
                setIsOpen(false);
            }
        };

        document.addEventListener('pointerdown', closeWhenClickingOutside);
        return () => document.removeEventListener('pointerdown', closeWhenClickingOutside);
    }, [isOpen]);

    useEffect(() => {
        if (!isOpen) return;

        // 打开菜单或切换键盘选项后，将焦点同步到当前选项。
        optionRefs.current[focusedIndex]?.focus();
    }, [focusedIndex, isOpen]);

    const openMenu = () => {
        if (disabled || !options.length) return;
        setFocusedIndex(selectedIndex);
        setIsOpen(true);
    };

    const closeMenu = () => {
        setIsOpen(false);

        // 关闭菜单后将焦点返回触发按钮，保持连续的键盘路径。
        triggerRef.current?.focus();
    };

    const selectOption = (option: SelectMenuOption) => {
        if (option.value !== value) {
            onChange(option.value);
        }
        closeMenu();
    };

    const moveFocus = (offset: number) => {
        setFocusedIndex((current) => (current + offset + options.length) % options.length);
    };

    return <div className={`select-menu ${className}`.trim()} ref={rootRef}>
        <button
            ref={triggerRef}
            className="select-menu-trigger"
            type="button"
            role="combobox"
            aria-label={ariaLabel}
            aria-haspopup="listbox"
            aria-controls={listboxId}
            aria-expanded={isOpen}
            disabled={disabled}
            onClick={() => isOpen ? closeMenu() : openMenu()}
            onKeyDown={(event) => {
                if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
                    event.preventDefault();
                    openMenu();
                }
            }}
        >
            <span className="select-menu-value">{selectedOption?.label ?? '请选择'}</span>
            <ChevronDown className="select-menu-chevron" size={13} aria-hidden="true"/>
        </button>

        {isOpen && <div id={listboxId} className="select-menu-options" role="listbox" aria-label={`${ariaLabel}选项`}>
            {options.map((option, index) => <button
                key={option.value}
                ref={(element) => {
                    optionRefs.current[index] = element;
                }}
                className="select-menu-option"
                type="button"
                role="option"
                aria-selected={option.value === value}
                onMouseEnter={() => setFocusedIndex(index)}
                onClick={() => selectOption(option)}
                onKeyDown={(event) => {
                    if (event.key === 'ArrowDown') {
                        event.preventDefault();
                        moveFocus(1);
                    } else if (event.key === 'ArrowUp') {
                        event.preventDefault();
                        moveFocus(-1);
                    } else if (event.key === 'Home') {
                        event.preventDefault();
                        setFocusedIndex(0);
                    } else if (event.key === 'End') {
                        event.preventDefault();
                        setFocusedIndex(options.length - 1);
                    } else if (event.key === 'Escape' || event.key === 'Tab') {
                        if (event.key === 'Escape') event.preventDefault();
                        closeMenu();
                    } else if (event.key === 'Enter' || event.key === ' ') {
                        event.preventDefault();
                        selectOption(option);
                    }
                }}
            >
                <span>{option.label}</span>
                {option.value === value && <Check className="select-menu-check" size={12} aria-hidden="true"/>}
            </button>)}
        </div>}
    </div>;
}
