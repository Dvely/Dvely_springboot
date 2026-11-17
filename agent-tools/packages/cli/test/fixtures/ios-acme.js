/** iOS 짚기 테스트가 같이 쓰는 접근성 트리(share-ios-pin·share-scope). 테스트 파일끼리 import 하면 테스트가 두 번 돈다. */
export const APP_PID = 52822;

const el = (type, role, frame, extra = {}, children = []) => ({
  type,
  role,
  subrole: null,
  AXLabel: null,
  AXUniqueId: null,
  AXValue: null,
  title: null,
  frame: { x: frame[0], y: frame[1], width: frame[2], height: frame[3] },
  children,
  ...extra,
});
const group = (frame, children, extra = {}) => el('Group', 'AXGroup', frame, extra, children);

/**
 * The SwiftUI example as `describe-ui --output json` printed it (AXe 2.0.4, iOS 26.5, iPhone 17 Pro
 * simulator), trimmed to what matters: wrapper groups as big as the screen, the navigation bar group
 * whose id is its title, section headings, two fields (one secure), a switch row, the button, the
 * scroll indicator over the right edge.
 */
export const ACME_TREE = {
  command: 'describe-ui',
  didError: false,
  data: {
    result: [
      el('Application', 'AXApplication', [0, 0, 402, 874], { AXLabel: 'Acme', pid: APP_PID }, [
        group([0, 0, 402, 874], [
          group([0, 62, 402, 106], [el('Heading', 'AXHeading', [16, 119.67, 88.33, 40.67], { AXLabel: '프로필' })], { AXUniqueId: '프로필' }),
          group([0, 0, 402, 874], [
            el('Heading', 'AXHeading', [16, 168, 370, 40.33], {}, [group([16, 168, 370, 40.33], [el('Heading', 'AXHeading', [16, 168, 370, 40.33], { AXLabel: '이름' })])]),
            group([16, 208.33, 370, 52.33], [el('TextField', 'AXTextField', [32, 223.33, 338, 22], { AXUniqueId: 'nameField', AXValue: '김민지' })]),
            group([16, 320, 370, 52.33], [
              el('TextField', 'AXTextField', [32, 335, 338, 20], { subrole: 'AXSecureTextField', AXUniqueId: 'passwordField', AXValue: '••••••••' }),
            ]),
            el('CheckBox', 'AXCheckBox', [16, 371, 370, 52], { subrole: 'AXSwitch', AXLabel: '알림 받기', AXValue: '1' }, [
              el('StaticText', 'AXStaticText', [32, 387, 63, 20], { AXLabel: '알림 받기' }),
              el('CheckBox', 'AXCheckBox', [309, 383, 63, 28], { subrole: 'AXSwitch', AXValue: '1' }),
            ]),
            group([16, 458, 370, 52.33], [group([16, 458, 370, 52.33], [el('Button', 'AXButton', [16, 458, 370, 52.33], { AXLabel: '저장', AXUniqueId: 'saveButton' })])]),
            el('StaticText', 'AXStaticText', [16, 568, 370, 52.33], { AXLabel: '지금은 무료 요금제를 쓰고 있습니다.', AXValue: '지금은 무료 요금제를 쓰고 있습니다.' }),
            el('Slider', 'AXSlider', [369, 116, 30, 696], { AXLabel: 'Vertical scroll bar, 1 pages', AXValue: '0%' }),
          ]),
        ]),
      ]),
    ],
  },
};
